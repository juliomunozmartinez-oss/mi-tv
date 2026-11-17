' Inicio de MuGa TV en Roku.
' Las filas de cada categoría se piden al servidor solo cuando se acercan a la pantalla.

sub init()
    m.tabNames = ["Inicio", "TV en vivo", "Películas", "Series", "Mi lista", "Buscar"]
    m.top10 = invalid
    m.searchResults = invalid
    m.searchQuery = ""
    ' Tarea que se queda viva: busca en todo el catálogo y arma el Top 10.
    m.index = CreateObject("roSGNode", "IndexTask")
    m.index.observeField("response", "onIndexResponse")
    m.index.control = "RUN"
    m.tab = 0
    m.focusArea = "tabs"
    m.gen = 0
    m.rowInfo = []
    m.cats = {}
    m.pending = 0
    m.queue = []
    m.tasks = {}

    m.rows = m.top.findNode("rows")
    m.hero = m.top.findNode("hero")
    m.heroLabel = m.top.findNode("heroLabel")
    m.heroTitle = m.top.findNode("heroTitle")
    m.heroInfo = m.top.findNode("heroInfo")
    m.heroPlot = m.top.findNode("heroPlot")
    m.message = m.top.findNode("message")
    m.tabLine = m.top.findNode("tabLine")
    m.infoTimer = m.top.findNode("infoTimer")
    m.heroTitle.font = "font:LargeBoldSystemFont"
    m.heroLabel.font = "font:SmallBoldSystemFont"

    tabs = m.top.findNode("tabs")
    m.tabLabels = []
    for each name in m.tabNames
        l = CreateObject("roSGNode", "Label")
        l.text = name
        l.font = "font:MediumBoldSystemFont"
        tabs.appendChild(l)
        m.tabLabels.Push(l)
    end for

    m.rows.observeField("rowItemFocused", "onItemFocused")
    m.rows.observeField("rowItemSelected", "onItemSelected")
    m.infoTimer.observeField("fire", "loadHeroInfo")
end sub

sub start()
    p = m.top.profile
    m.top.findNode("avatar").uri = AvatarUri(p.avatar)
    m.cats = {}
    m.catsFailed = false
    m.tab = 0
    m.top10 = invalid
    m.searchResults = invalid
    m.searchQuery = ""
    paintTabs()
    m.message.text = "Cargando tu catálogo…"
    ' Las tres listas de categorías se piden al mismo tiempo.
    request("cats:live", "json", ApiUrl("get_live_categories", ""), "")
    request("cats:movie", "json", ApiUrl("get_vod_categories", ""), "")
    request("cats:series", "json", ApiUrl("get_series_categories", ""), "")
end sub

sub restoreFocus()
    if m.focusArea = "rows" and m.rows.content <> invalid and m.rows.content.getChildCount() > 0 then
        m.rows.setFocus(true)
    else
        m.focusArea = "tabs"
        m.top.setFocus(true)
    end if
    paintTabs()
end sub

' ---------- Consultas ----------

sub request(id as string, kind as string, url as string, itemKind as string)
    t = CreateObject("roSGNode", "ApiTask")
    t.observeField("response", "onResponse")
    t.request = { id: id, kind: kind, url: url, itemKind: itemKind }
    t.control = "RUN"
    m.tasks[id] = t
end sub

sub onResponse(msg as object)
    res = msg.getData()
    id = res.id
    m.tasks.Delete(id)
    if Left(id, 5) = "cats:" then
        kind = Mid(id, 6)
        m.cats[kind] = filterCats(kind, res.data)
        if not res.ok then m.catsFailed = true
        if m.cats.live <> invalid and m.cats.movie <> invalid and m.cats.series <> invalid then
            render()
            ' El Top 10 llega después, sin hacer esperar la pantalla.
            m.index.request = { id: "top", type: "top", movieCats: catDict("movie"), seriesCats: catDict("series") }
            empty = m.cats.movie.Count() = 0 and m.cats.series.Count() = 0 and m.cats.live.Count() = 0
            if empty and m.catsFailed = true then
                m.message.text = "No se pudo conectar con el servidor del proveedor." + Chr(10) + "Presiona Atrás, entra a «Ajustes» y pega el enlace de flowpremium (puerto 2087)."
            end if
        end if
    else if Left(id, 4) = "row:" then
        parts = id.Split(":")
        gen = parts[1].ToInt()
        idx = parts[2].ToInt()
        m.pending = m.pending - 1
        if gen = m.gen then fillRow(idx, res.content)
        pump()
    else if Left(id, 5) = "info:" then
        showInfo(res.data)
    end if
end sub

function filterCats(kind as string, data as dynamic) as object
    out = []
    if type(data) <> "roArray" then return out
    kids = (m.top.profile.kids = true)
    mexico = []
    for each c in data
        name = ToStr(c.category_name)
        if not kids or IsKidsCategory(name) then
            entry = { id: ToStr(c.category_id), name: name }
            if kind = "live" and IsMexicoCategory(name) then
                mexico.Push(entry)
            else
                out.Push(entry)
            end if
        end if
    end for
    ' Canales de México primero.
    mexico.Append(out)
    return mexico
end function

' ---------- Pestañas ----------

sub paintTabs()
    for i = 0 to m.tabLabels.Count() - 1
        if i = m.tab then
            m.tabLabels[i].color = "0xFFFFFFFF"
        else
            m.tabLabels[i].color = "0x94A3B8FF"
        end if
    end for
    l = m.tabLabels[m.tab]
    r = l.boundingRect()
    m.tabLine.visible = (m.focusArea = "tabs")
    m.tabLine.width = r.width
    m.tabLine.translation = [360 + r.x, 100]
end sub

' ---------- Filas ----------

sub render()
    m.gen = m.gen + 1
    m.queue = []
    m.rowInfo = []
    root = CreateObject("roSGNode", "ContentNode")
    sizes = []
    pid = m.top.profile.id

    if m.tab = 0 then
        addResumeRow(root, sizes, pid)
        addTopRow(root, sizes, "películas")
        addTopRow(root, sizes, "series")
        addMyListRows(root, sizes, pid, true)
        n = 0
        for each c in m.cats.movie
            if n < 5 then addCategoryRow(root, sizes, c, "movie")
            n = n + 1
        end for
        n = 0
        for each c in m.cats.series
            if n < 4 then addCategoryRow(root, sizes, c, "series")
            n = n + 1
        end for
    else if m.tab = 1 then
        for each c in m.cats.live
            addCategoryRow(root, sizes, c, "live")
        end for
    else if m.tab = 2 then
        addTopRow(root, sizes, "películas")
        for each c in m.cats.movie
            addCategoryRow(root, sizes, c, "movie")
        end for
    else if m.tab = 3 then
        addTopRow(root, sizes, "series")
        for each c in m.cats.series
            addCategoryRow(root, sizes, c, "series")
        end for
    else if m.tab = 4 then
        addMyListRows(root, sizes, pid, false)
    else if m.tab = 5 then
        if m.searchResults <> invalid then
            for i = 0 to m.searchResults.getChildCount() - 1
                root.appendChild(m.searchResults.getChild(i).clone(true))
                sizes.Push([200, 300])
                m.rowInfo.Push({ loaded: true })
            end for
        end if
    end if

    if root.getChildCount() = 0 then
        if m.tab = 5 then
            if m.searchQuery <> "" and m.searchResults <> invalid then
                m.message.text = "No encontré nada con «" + m.searchQuery + "». Presiona OK para buscar otra cosa."
            else if m.searchQuery <> "" then
                m.message.text = "Buscando «" + m.searchQuery + "»…" + Chr(10) + "La primera vez tarda un poco porque revisa todo el catálogo."
            else
                m.message.text = "Presiona OK para buscar una película o serie."
            end if
        else if m.tab = 4 then
            m.message.text = "Aún no tienes nada en Mi lista. En la ficha de una película o serie elige «+ Mi lista»."
        else
            m.message.text = "No hay contenido en esta sección."
        end if
        m.rows.content = invalid
        setHero("", "", "", "", "")
        return
    end if
    m.message.text = ""
    m.rows.rowItemSize = sizes
    m.rows.content = root
    m.rows.jumpToRowItem = [0, 0]
    loadAround(0)
    showFirstHero()
end sub

' Fila del Top 10 (si ya llegó). kindWord = "películas" o "series".
sub addTopRow(root as object, sizes as object, kindWord as string)
    if m.top10 = invalid then return
    for i = 0 to m.top10.getChildCount() - 1
        row = m.top10.getChild(i)
        if Instr(1, row.title, kindWord) > 0 then
            root.appendChild(row.clone(true))
            sizes.Push([200, 300])
            m.rowInfo.Push({ loaded: true })
        end if
    end for
end sub

' Categorías que puede ver este perfil (invalid = todas, para adultos).
function catDict(kind as string) as dynamic
    if m.top.profile.kids <> true then return invalid
    d = {}
    for each c in m.cats[kind]
        d[c.id] = true
    end for
    return d
end function

sub onIndexResponse(msg as object)
    res = msg.getData()
    if res.type = "top" then
        if res.ok and res.content <> invalid and res.content.getChildCount() > 0 then
            m.top10 = res.content
            ' Solo se redibuja si nadie está moviéndose por las filas (para no mover el foco).
            if m.focusArea = "tabs" and (m.tab = 0 or m.tab = 2 or m.tab = 3) then render()
        end if
    else if res.type = "search" then
        if res.ok then
            m.searchResults = res.content
        else
            m.searchResults = CreateObject("roSGNode", "ContentNode")
        end if
        if m.tab = 5 then
            render()
            if not res.ok then m.message.text = res.message
            if m.rows.content <> invalid and m.rows.content.getChildCount() > 0 then
                m.focusArea = "rows"
                m.rows.setFocus(true)
                paintTabs()
            end if
        end if
    end if
end sub

' ---------- Buscar ----------

sub openSearch()
    d = CreateObject("roSGNode", "StandardKeyboardDialog")
    d.title = "Buscar película o serie"
    d.text = m.searchQuery
    d.buttons = ["Buscar", "Cancelar"]
    d.observeField("buttonSelected", "onSearchDialog")
    m.top.getScene().dialog = d
end sub

sub onSearchDialog()
    d = m.top.getScene().dialog
    if d = invalid then return
    ok = (d.buttonSelected = 0)
    q = d.text.Trim()
    d.close = true
    m.focusArea = "tabs"
    m.top.setFocus(true)
    if not ok or q = "" then return
    m.searchQuery = q
    m.searchResults = invalid
    render()
    m.index.request = { id: "search", type: "search", q: q, movieCats: catDict("movie"), seriesCats: catDict("series") }
end sub

sub addCategoryRow(root as object, sizes as object, c as object, itemKind as string)
    row = root.CreateChild("ContentNode")
    row.title = c.name
    ph = row.CreateChild("ContentNode")
    ph.title = "Cargando…"
    ph.addFields({ itemKind: "placeholder" })
    sizes.Push(sizeFor(itemKind))
    action = "get_vod_streams"
    if itemKind = "series" then action = "get_series"
    if itemKind = "live" then action = "get_live_streams"
    m.rowInfo.Push({ loaded: false, url: ApiUrl(action, "&category_id=" + c.id), itemKind: itemKind, title: c.name })
end sub

function sizeFor(itemKind as string) as object
    if itemKind = "live" then return [320, 180]
    return [200, 300]
end function

sub addResumeRow(root as object, sizes as object, pid as string)
    list = Resume_All(pid)
    if list.Count() = 0 then return
    row = root.CreateChild("ContentNode")
    row.title = "Seguir viendo"
    for each e in list
        n = row.CreateChild("ContentNode")
        n.title = e.title
        n.HDPosterUrl = e.poster
        prog = -1.0
        if e.dur <> invalid and e.dur > 0 then prog = e.pos / e.dur
        n.addFields({ itemKind: "resume", entry: FormatJson(e), progress: prog })
    end for
    sizes.Push([200, 300])
    m.rowInfo.Push({ loaded: true })
end sub

sub addMyListRows(root as object, sizes as object, pid as string, mixed as boolean)
    list = MyList_All(pid)
    if list.Count() = 0 then return
    groups = [["series", "Series"], ["movie", "Películas"], ["live", "TV en vivo"]]
    if mixed then groups = [["*", "Mi lista"]]
    for each g in groups
        row = CreateObject("roSGNode", "ContentNode")
        row.title = g[1]
        for each e in list
            if g[0] = "*" or e.kind = g[0] then
                n = row.CreateChild("ContentNode")
                n.id = e.id
                n.title = e.title
                n.HDPosterUrl = e.poster
                n.addFields({ itemKind: e.kind, ext: ToStr(e.ext), inList: "1" })
            end if
        end for
        if row.getChildCount() > 0 then
            root.appendChild(row)
            if g[0] = "live" then sizes.Push([320, 180]) else sizes.Push([200, 300])
            m.rowInfo.Push({ loaded: true })
        end if
    end for
end sub

' Pide las filas cercanas a la que tiene el foco (máximo 3 consultas a la vez).
sub loadAround(rowIdx as integer)
    last = rowIdx + 3
    if last > m.rowInfo.Count() - 1 then last = m.rowInfo.Count() - 1
    for i = rowIdx to last
        info = m.rowInfo[i]
        if not info.loaded and info.queued = invalid then
            info.queued = true
            m.queue.Push(i)
        end if
    end for
    pump()
end sub

sub pump()
    while m.pending < 3 and m.queue.Count() > 0
        i = m.queue.Shift()
        info = m.rowInfo[i]
        m.pending = m.pending + 1
        request("row:" + m.gen.ToStr() + ":" + i.ToStr(), "items", info.url, info.itemKind)
    end while
end sub

sub fillRow(idx as integer, content as object)
    root = m.rows.content
    if root = invalid or idx >= root.getChildCount() then return
    info = m.rowInfo[idx]
    info.loaded = true
    content.title = info.title
    if content.getChildCount() = 0 then
        e = content.CreateChild("ContentNode")
        e.title = "Sin títulos"
        e.addFields({ itemKind: "placeholder" })
    end if
    root.replaceChild(content, idx)
    if idx = 0 then showFirstHero()
end sub

' ---------- Destacado (parte de arriba) ----------

sub showFirstHero()
    root = m.rows.content
    if root = invalid or root.getChildCount() = 0 then return
    item = root.getChild(0).getChild(0)
    if item <> invalid then showHeroFor(item)
end sub

sub setHero(label as string, title as string, info as string, plot as string, image as string)
    m.heroLabel.text = label
    m.heroTitle.text = title
    m.heroInfo.text = info
    m.heroPlot.text = plot
    m.hero.uri = image
end sub

sub showHeroFor(item as object)
    kind = FldStr(item, "itemKind")
    if kind = "placeholder" then return
    m.heroItem = item
    label = "PELÍCULA"
    if kind = "series" then label = "SERIE"
    if kind = "live" then label = "EN VIVO"
    if kind = "resume" then label = "SEGUIR VIENDO"
    info = ""
    rating = FldStr(item, "rating")
    if rating <> "" and rating <> "0" then info = "★ " + rating
    image = item.HDPosterUrl
    if kind = "live" then image = ""
    setHero(label, item.title, info, item.description, image)
    m.infoTimer.control = "stop"
    if kind = "movie" or kind = "series" then m.infoTimer.control = "start"
end sub

' Trae la imagen horizontal y la sinopsis del título enfocado.
sub loadHeroInfo()
    item = m.heroItem
    if item = invalid then return
    kind = FldStr(item, "itemKind")
    if kind = "movie" then
        request("info:" + item.id, "json", ApiUrl("get_vod_info", "&vod_id=" + item.id), "")
    else if kind = "series" then
        request("info:" + item.id, "json", ApiUrl("get_series_info", "&series_id=" + item.id), "")
    end if
end sub

sub showInfo(data as dynamic)
    if data = invalid or m.heroItem = invalid then return
    info = data.info
    if info = invalid then return
    parts = []
    year = ToStr(info.releasedate)
    if year = "" then year = ToStr(info.releaseDate)
    if year.Len() >= 4 then parts.Push(Left(year, 4))
    r = ToStr(info.rating)
    if r <> "" and r <> "0" then parts.Push("★ " + r)
    g = ToStr(info.genre)
    if g <> "" then parts.Push(g)
    m.heroInfo.text = parts.Join("   ·   ")
    plot = ToStr(info.plot)
    if plot <> "" then m.heroPlot.text = plot
    backdrop = info.backdrop_path
    if type(backdrop) = "roArray" and backdrop.Count() > 0 then
        m.hero.uri = ToStr(backdrop[0])
    else if ToStr(backdrop) <> "" then
        m.hero.uri = ToStr(backdrop)
    end if
end sub

' ---------- Eventos de las filas ----------

sub onItemFocused()
    f = m.rows.rowItemFocused
    root = m.rows.content
    if root = invalid then return
    row = root.getChild(f[0])
    if row = invalid then return
    item = row.getChild(f[1])
    if item <> invalid then showHeroFor(item)
    loadAround(f[0])
end sub

sub onItemSelected()
    s = m.rows.rowItemSelected
    row = m.rows.content.getChild(s[0])
    item = row.getChild(s[1])
    kind = FldStr(item, "itemKind")
    if kind = "placeholder" then return
    if kind = "resume" then
        m.top.action = { type: "resume", entry: ParseJson(FldStr(item, "entry")) }
    else if kind = "live" then
        channels = []
        for i = 0 to row.getChildCount() - 1
            c = row.getChild(i)
            if FldStr(c, "itemKind") = "live" then channels.Push({ id: c.id, title: c.title, poster: c.HDPosterUrl })
        end for
        index = 0
        for i = 0 to channels.Count() - 1
            if channels[i].id = item.id then index = i
        end for
        m.top.action = { type: "live", channels: channels, index: index }
    else
        m.top.action = {
            type: "open",
            item: { kind: kind, id: item.id, title: item.title, poster: item.HDPosterUrl, ext: FldStr(item, "ext"), plot: item.description }
        }
    end if
end sub

' Al volver de ver algo: actualizar «Seguir viendo» y «Mi lista» sin perder el lugar.
sub onRefreshMine()
    if m.tab <> 0 and m.tab <> 4 then return
    f = m.rows.rowItemFocused
    render()
    if f <> invalid and m.rows.content <> invalid and f[0] < m.rows.content.getChildCount() then
        m.rows.jumpToRowItem = f
    end if
end sub

' ---------- Control remoto ----------

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if m.focusArea = "tabs" then
        if key = "left" and m.tab > 0 then
            m.tab = m.tab - 1
            paintTabs()
            render()
            return true
        else if key = "right" and m.tab < m.tabNames.Count() - 1 then
            m.tab = m.tab + 1
            paintTabs()
            render()
            return true
        else if m.tab = 5 and (key = "OK" or (key = "down" and m.rows.content = invalid)) then
            openSearch()
            return true
        else if (key = "down" or key = "OK") and m.rows.content <> invalid then
            m.focusArea = "rows"
            m.rows.setFocus(true)
            paintTabs()
            return true
        end if
        return false
    end if
    ' En las filas: Atrás regresa de un salto a la primera fila y a las pestañas;
    ' arriba desde la primera fila también.
    if key = "back" then
        if m.rows.content <> invalid and m.rows.content.getChildCount() > 0 then m.rows.jumpToRowItem = [0, 0]
        m.focusArea = "tabs"
        m.top.setFocus(true)
        paintTabs()
        return true
    end if
    if key = "up" then
        m.focusArea = "tabs"
        m.top.setFocus(true)
        paintTabs()
        return true
    end if
    return false
end function
