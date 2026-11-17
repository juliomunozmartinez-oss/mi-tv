sub init()
    m.top.functionName = "runLoop"
end sub

sub runLoop()
    port = CreateObject("roMessagePort")
    m.top.observeField("request", port)
    m.entries = invalid
    m.trending = invalid
    m.noise = CreateObject("roRegex", "\b(4k|uhd|fhd|hd|sd|hevc|x265|lat|latino|cast|castellano|esp|es|sub|subt|subtitulado|dual|multi|en|eng|mx|vip|the|el|la|los|las|de|del|un|una|y)\b", "")
    m.years = CreateObject("roRegex", "\b(19|20)\d\d\b", "")
    m.tags = CreateObject("roRegex", "\[[^\]]*\]|\([^\)]*\)", "")
    m.nonAlnum = CreateObject("roRegex", "[^a-z0-9]+", "")
    m.spaces = CreateObject("roRegex", "\s+", "")
    m.accents = [
        [CreateObject("roRegex", "á|à|ä|â|ã|Á|À|Ä|Â", ""), "a"], [CreateObject("roRegex", "é|è|ë|ê|É|È", ""), "e"],
        [CreateObject("roRegex", "í|ì|ï|î|Í", ""), "i"], [CreateObject("roRegex", "ó|ò|ö|ô|õ|Ó|Ö", ""), "o"],
        [CreateObject("roRegex", "ú|ù|ü|û|Ú|Ü", ""), "u"], [CreateObject("roRegex", "ñ|Ñ", ""), "n"]
    ]
    while true
        msg = wait(0, port)
        if type(msg) = "roSGNodeEvent" then
            req = msg.getData()
            if type(req) = "roAssociativeArray" then handleRequest(req)
        end if
    end while
end sub

sub handleRequest(req as object)
    res = { id: req.id, type: req.type, ok: false, message: "" }
    if not ensureIndex() then
        res.message = "No se pudo bajar el catálogo para buscar. Intenta de nuevo."
        m.top.response = res
        return
    end if
    if req.type = "search" then
        res.content = search(ToStr(req.q), req.movieCats, req.seriesCats)
        res.ok = true
    else if req.type = "top" then
        res.content = topRows(req.movieCats, req.seriesCats)
        res.ok = res.content <> invalid
    end if
    m.top.response = res
end sub

' ---------- Texto ----------

' Deja un título «limpio» y con espacios a los lados: " proyecto fin mundo ".
function Norm(s as string) as string
    t = LCase(s)
    for each a in m.accents
        t = a[0].ReplaceAll(t, a[1])
    end for
    t = m.tags.ReplaceAll(t, " ")
    t = m.nonAlnum.ReplaceAll(t, " ")
    t = m.years.ReplaceAll(t, " ")
    t = m.noise.ReplaceAll(t, " ")
    t = m.spaces.ReplaceAll(t, " ").Trim()
    return " " + t + " "
end function

' ---------- Catálogo completo ----------

function ensureIndex() as boolean
    if m.entries <> invalid then return true
    m.relaxed = (Store_Get("tls_relaxed", "0") = "1")
    entries = []
    for each kind in ["movie", "series"]
        action = "get_vod_streams"
        catAction = "get_vod_categories"
        if kind = "series" then
            action = "get_series"
            catAction = "get_series_categories"
        end if
        ' Primero todo de una vez; si es demasiado grande para el Roku, categoría por categoría.
        if not addFrom(entries, kind, ApiUrl(action, "")) then
            r = HttpGet(ApiUrl(catAction, ""), m.relaxed)
            if r.body = invalid then return false
            cats = ParseJson(r.body)
            if type(cats) <> "roArray" then return false
            for each c in cats
                addFrom(entries, kind, ApiUrl(action, "&category_id=" + ToStr(c.category_id)))
            end for
        end if
    end for
    m.entries = entries
    return true
end function

function addFrom(entries as object, kind as string, url as string) as boolean
    r = HttpGet(url, m.relaxed)
    if r.body = invalid then return false
    data = ParseJson(r.body)
    r = invalid
    if type(data) <> "roArray" then return false
    for each it in data
        e = { k: kind, t: ToStr(it.name), c: ToStr(it.category_id) }
        if kind = "series" then
            e.id = ToStr(it.series_id)
            e.p = ToStr(it.cover)
        else
            e.id = ToStr(it.stream_id)
            e.p = ToStr(it.stream_icon)
            e.e = ToStr(it.container_extension)
        end if
        e.n = Norm(e.t)
        entries.Push(e)
    end for
    return true
end function

function allowed(e as object, movieCats as dynamic, seriesCats as dynamic) as boolean
    cats = movieCats
    if e.k = "series" then cats = seriesCats
    if type(cats) <> "roAssociativeArray" then return true
    return cats.DoesExist(e.c)
end function

function newItem(row as object, e as object) as object
    n = row.CreateChild("ContentNode")
    n.id = e.id
    n.title = e.t
    n.HDPosterUrl = e.p
    n.addFields({ itemKind: e.k, ext: ToStr(e.e) })
    return n
end function

' ---------- Buscar ----------

function search(q as string, movieCats as dynamic, seriesCats as dynamic) as object
    root = CreateObject("roSGNode", "ContentNode")
    nq = Norm(q).Trim()
    if nq = "" then return root
    toks = nq.Split(" ")
    whole = " " + nq + " "
    found = { movie: [], series: [] }
    for each e in m.entries
        hit = true
        for each tk in toks
            if Instr(1, e.n, " " + tk) = 0 then
                hit = false
                exit for
            end if
        end for
        if hit and allowed(e, movieCats, seriesCats) then
            score = 2
            if e.n = whole then
                score = 0
            else if Left(e.n, whole.Len() - 1) = Left(whole, whole.Len() - 1) then
                score = 1
            end if
            found[e.k].Push({ s: score, e: e })
        end if
    end for
    for each g in [["movie", "Películas"], ["series", "Series"]]
        list = found[g[0]]
        list.SortBy("s")
        if list.Count() > 0 then
            row = root.CreateChild("ContentNode")
            row.title = g[1] + " (" + list.Count().ToStr() + ")"
            n = 0
            for each x in list
                if n < 60 then newItem(row, x.e)
                n = n + 1
            end for
        end if
    end for
    return root
end function

' ---------- Top 10 de la semana ----------

function topRows(movieCats as dynamic, seriesCats as dynamic) as object
    if m.trending = invalid then
        r = HttpGet("https://github.com/juliomunozmartinez-oss/mi-tv/releases/download/trending/trending.json", false)
        if r.body = invalid then return invalid
        m.trending = ParseJson(r.body)
        if m.trending = invalid then return invalid
    end if
    ' Títulos «limpios» del catálogo de este perfil → el primero que aparezca.
    byKey = { movie: {}, series: {} }
    for each e in m.entries
        if allowed(e, movieCats, seriesCats) then
            d = byKey[e.k]
            if not d.DoesExist(e.n) then d[e.n] = e
        end if
    end for
    root = CreateObject("roSGNode", "ContentNode")
    for each g in [["movies", "movie", "Top 10 películas de la semana"], ["series", "series", "Top 10 series de la semana"]]
        list = m.trending[g[0]]
        if type(list) = "roArray" then
            row = CreateObject("roSGNode", "ContentNode")
            row.title = g[2]
            used = {}
            for each t in list
                if row.getChildCount() < 10 then
                    e = invalid
                    k1 = Norm(ToStr(t.t))
                    k2 = Norm(ToStr(t.o))
                    if byKey[g[1]].DoesExist(k1) then
                        e = byKey[g[1]][k1]
                    else if byKey[g[1]].DoesExist(k2) then
                        e = byKey[g[1]][k2]
                    end if
                    if e <> invalid and not used.DoesExist(e.id) then
                        used[e.id] = true
                        it = newItem(row, e)
                        it.addFields({ rank: row.getChildCount() })
                    end if
                end if
            end for
            if row.getChildCount() > 0 then root.appendChild(row)
        end if
    end for
    return root
end function
