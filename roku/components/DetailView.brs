' Ficha de una película o serie.

sub init()
    m.backdrop = m.top.findNode("backdrop")
    m.cover = m.top.findNode("cover")
    m.titleL = m.top.findNode("title")
    m.info = m.top.findNode("info")
    m.plot = m.top.findNode("plot")
    m.buttons = m.top.findNode("buttons")
    m.episodes = m.top.findNode("episodes")
    m.epTitle = m.top.findNode("epTitle")
    m.loading = m.top.findNode("loading")
    m.titleL.font = "font:LargeBoldSystemFont"
    m.buttons.observeField("buttonSelected", "onButton")
    m.episodes.observeField("itemSelected", "onEpisode")
    m.eps = []
end sub

sub load()
    it = m.top.item
    m.eps = []
    m.ext = it.ext
    m.titleL.text = it.title
    m.info.text = ""
    m.plot.text = ToStr(it.plot)
    m.cover.uri = it.poster
    m.backdrop.uri = it.poster
    m.episodes.visible = false
    m.epTitle.visible = false
    m.loading.visible = true
    updateButtons()
    if it.kind = "series" then
        request(ApiUrl("get_series_info", "&series_id=" + it.id))
    else
        request(ApiUrl("get_vod_info", "&vod_id=" + it.id))
    end if
end sub

sub restoreFocus()
    m.buttons.setFocus(true)
end sub

sub request(url as string)
    t = CreateObject("roSGNode", "ApiTask")
    t.observeField("response", "onInfo")
    t.request = { id: "detail", kind: "json", url: url }
    t.control = "RUN"
    m.task = t
end sub

sub onInfo(msg as object)
    m.loading.visible = false
    res = msg.getData()
    data = res.data
    if data = invalid then return
    info = data.info
    if info <> invalid then
        parts = []
        year = ToStr(info.releasedate)
        if year = "" then year = ToStr(info.releaseDate)
        if year.Len() >= 4 then parts.Push(Left(year, 4))
        r = ToStr(info.rating)
        if r <> "" and r <> "0" then parts.Push("★ " + r)
        d = ToStr(info.duration)
        if d <> "" then parts.Push(d)
        g = ToStr(info.genre)
        if g <> "" then parts.Push(g)
        m.info.text = parts.Join("   ·   ")
        plot = ToStr(info.plot)
        if plot <> "" then m.plot.text = plot
        backdrop = info.backdrop_path
        if type(backdrop) = "roArray" and backdrop.Count() > 0 then m.backdrop.uri = ToStr(backdrop[0])
    end if
    if data.movie_data <> invalid then
        ext = ToStr(data.movie_data.container_extension)
        if ext <> "" then m.ext = ext
    end if
    if m.top.item.kind = "series" then buildEpisodes(data.episodes)
    updateButtons()
    m.buttons.setFocus(true)
end sub

' Junta todos los episodios en orden (temporada, número).
sub buildEpisodes(eps as dynamic)
    list = []
    if type(eps) = "roAssociativeArray" then
        for each season in eps
            arr = eps[season]
            if type(arr) = "roArray" then
                for each e in arr
                    list.Push(episodeEntry(e, season.ToInt()))
                end for
            end if
        end for
    else if type(eps) = "roArray" then
        s = 1
        for each arr in eps
            if type(arr) = "roArray" then
                for each e in arr
                    list.Push(episodeEntry(e, s))
                end for
            end if
            s = s + 1
        end for
    end if
    list.SortBy("order")
    m.eps = list
    root = CreateObject("roSGNode", "ContentNode")
    for each e in list
        n = root.CreateChild("ContentNode")
        n.title = "T" + e.season.ToStr() + ":E" + e.num.ToStr() + "  ·  " + e.title
    end for
    m.episodes.content = root
    m.episodes.visible = list.Count() > 0
    m.epTitle.visible = list.Count() > 0
end sub

function episodeEntry(e as object, fallbackSeason as integer) as object
    season = fallbackSeason
    if e.season <> invalid then season = ToStr(e.season).ToInt()
    num = ToStr(e.episode_num).ToInt()
    ext = ToStr(e.container_extension)
    image = ""
    if e.info <> invalid then image = ToStr(e.info.movie_image)
    return {
        id: ToStr(e.id),
        season: season,
        num: num,
        title: ToStr(e.title),
        ext: ext,
        image: image,
        order: season * 10000 + num
    }
end function

function itemKey() as string
    it = m.top.item
    if it.kind = "series" then return "series:" + it.id
    return "movie:" + it.id
end function

' Botones: Reproducir/Continuar, Desde el inicio, Mi lista.
sub updateButtons()
    it = m.top.item
    if it = invalid or m.top.profile = invalid then return
    pid = m.top.profile.id
    r = Resume_Find(pid, itemKey())
    first = "▶  Reproducir"
    m.resume = r
    if it.kind = "series" then
        first = "▶  Ver episodio 1"
        if r <> invalid then first = "▶  Continuar  " + ToStr(r.epLabel)
    else if r <> invalid and r.pos <> invalid and r.pos > 0 then
        first = "▶  Continuar"
    end if
    listLabel = "+  Mi lista"
    if MyList_Has(pid, itemKey()) then listLabel = "✓  En mi lista"
    m.buttons.buttons = [first, "↺  Desde el inicio", listLabel]
end sub

sub onButton()
    i = m.buttons.buttonSelected
    it = m.top.item
    if i = 0 then
        if it.kind = "series" then
            startEpisode(resumeIndex(), true)
        else
            start = 0
            if m.resume <> invalid then start = m.resume.pos
            playMovie(start)
        end if
    else if i = 1 then
        if it.kind = "series" then
            startEpisode(0, false)
        else
            playMovie(0)
        end if
    else if i = 2 then
        entry = { k: itemKey(), kind: it.kind, id: it.id, title: it.title, poster: it.poster, ext: m.ext }
        MyList_Toggle(m.top.profile.id, entry)
        updateButtons()
        m.buttons.focusButton = 2
    end if
end sub

function resumeIndex() as integer
    if m.resume = invalid or m.resume.ep = invalid then return 0
    for i = 0 to m.eps.Count() - 1
        if m.eps[i].id = m.resume.ep then return i
    end for
    return 0
end function

sub onEpisode()
    startEpisode(m.episodes.itemSelected, true)
end sub

sub startEpisode(index as integer, useResume as boolean)
    if m.eps.Count() = 0 then return
    start = 0
    if useResume and m.resume <> invalid then
        if ToStr(m.resume.ep) = m.eps[index].id then start = m.resume.pos
    end if
    it = m.top.item
    m.top.action = {
        type: "play", kind: "episode", seriesId: it.id, seriesTitle: it.title, poster: it.poster,
        episodes: m.eps, index: index, start: start
    }
end sub

sub playMovie(start as integer)
    it = m.top.item
    m.top.action = {
        type: "play", kind: "movie", id: it.id, ext: m.ext, title: it.title, poster: it.poster, start: start
    }
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if key = "down" and m.buttons.hasFocus() and m.episodes.visible then
        m.episodes.setFocus(true)
        return true
    else if key = "up" and m.episodes.hasFocus() and m.episodes.itemFocused = 0 then
        m.buttons.setFocus(true)
        return true
    end if
    return false
end function
