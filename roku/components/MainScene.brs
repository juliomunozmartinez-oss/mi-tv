' Escena principal: navega entre perfiles, inicio, ficha y reproductor.

sub init()
    m.profilesView = m.top.findNode("profilesView")
    m.homeView = m.top.findNode("homeView")
    m.detailView = m.top.findNode("detailView")
    m.video = m.top.findNode("video")
    m.osd = m.top.findNode("osd")
    m.osdText = m.top.findNode("osdText")
    m.osdTimer = m.top.findNode("osdTimer")
    m.osdText.font = "font:MediumBoldSystemFont"

    m.profilesView.observeField("chosen", "onProfileChosen")
    m.homeView.observeField("action", "onHomeAction")
    m.detailView.observeField("action", "onDetailAction")
    m.video.observeField("state", "onVideoState")
    m.video.observeField("position", "onVideoPosition")
    m.osdTimer.observeField("fire", "hideOsd")

    m.playing = invalid
    m.live = invalid
    m.lastSave = 0
    showScreen("profiles")
    if not Account_IsSet() then openSettings()
end sub

sub showScreen(name as string)
    m.current = name
    m.profilesView.visible = (name = "profiles")
    m.homeView.visible = (name = "home")
    m.detailView.visible = (name = "detail")
    if name = "profiles" then
        m.profilesView.refresh = true
        m.profilesView.takeFocus = true
    else if name = "home" then
        m.homeView.takeFocus = true
    else if name = "detail" then
        m.detailView.takeFocus = true
    end if
end sub

' ---------- Perfiles y ajustes ----------

sub onProfileChosen()
    c = m.profilesView.chosen
    if c.type = "settings" then
        openSettings()
        return
    end if
    if not Account_IsSet() then
        openSettings()
        return
    end if
    m.profile = c.profile
    m.detailView.profile = c.profile
    m.homeView.profile = c.profile
    showScreen("home")
end sub

sub openSettings()
    a = Account_Get()
    d = CreateObject("roSGNode", "StandardKeyboardDialog")
    d.title = "Cuenta del proveedor"
    d.message = ["Pega el enlace de tu lista (get.php o /playlist/usuario/contraseña/…) o escribe solo el servidor, con http(s):// y el puerto."]
    d.text = a.server
    d.buttons = ["Siguiente", "Cancelar"]
    d.observeField("buttonSelected", "onSettingsLink")
    m.top.dialog = d
end sub

sub onSettingsLink()
    d = m.top.dialog
    if d.buttonSelected <> 0 then
        d.close = true
        focusCurrent()
        return
    end if
    text = d.text.Trim()
    d.close = true
    parsed = ParseProviderLink(text)
    if parsed <> invalid then
        saveAccount(parsed.server, parsed.user, parsed.pass)
        return
    end if
    if text = "" then
        focusCurrent()
        return
    end if
    m.newServer = text
    askField("Usuario", Store_Get("user", ""), "onSettingsUser")
end sub

sub askField(title as string, value as string, handler as string)
    d = CreateObject("roSGNode", "StandardKeyboardDialog")
    d.title = title
    d.text = value
    d.buttons = ["Siguiente", "Cancelar"]
    d.observeField("buttonSelected", handler)
    m.top.dialog = d
end sub

sub onSettingsUser()
    d = m.top.dialog
    ok = (d.buttonSelected = 0)
    m.newUser = d.text.Trim()
    d.close = true
    if ok and m.newUser <> "" then
        askField("Contraseña", Store_Get("pass", ""), "onSettingsPass")
    else
        focusCurrent()
    end if
end sub

sub onSettingsPass()
    d = m.top.dialog
    ok = (d.buttonSelected = 0)
    pass = d.text.Trim()
    d.close = true
    if ok and pass <> "" then
        saveAccount(m.newServer, m.newUser, pass)
    else
        focusCurrent()
    end if
end sub

sub saveAccount(server as string, user as string, pass as string)
    Store_Set("server", NormalizeServer(server))
    Store_Set("user", user)
    Store_Set("pass", pass)
    t = CreateObject("roSGNode", "ApiTask")
    t.observeField("response", "onLoginResult")
    t.request = { id: "login", kind: "json", url: ApiUrl("", "") }
    t.control = "RUN"
    m.loginTask = t
    message("Probando conexión…", "Un momento.")
end sub

sub onLoginResult(msg as object)
    res = msg.getData()
    ok = false
    if res.data <> invalid and res.data.user_info <> invalid then ok = (ToStr(res.data.user_info.auth) = "1")
    if ok then
        ' Si solo funcionó sin «s», guardar el servidor con http:// para todo lo demás.
        if res.usedHttp = true then
            server = Store_Get("server", "")
            if LCase(Left(server, 8)) = "https://" then Store_Set("server", "http://" + Mid(server, 9))
        end if
        message("✔ Conectado", "La cuenta funciona. Elige un perfil.")
    else if res.data <> invalid then
        message("✖ Usuario o contraseña incorrectos", "El servidor respondió, pero no aceptó la cuenta. Revisa el enlace en «Ajustes».")
    else
        detail = "Código " + ToStr(res.code)
        if ToStr(res.reason) <> "" then detail = detail + ": " + ToStr(res.reason)
        message("✖ No se pudo conectar", "Revisa el servidor (http o https y el puerto). Puedes cambiarlo en «Ajustes»." + Chr(10) + Chr(10) + "Detalle: " + detail)
    end if
end sub

sub message(title as string, text as string)
    d = CreateObject("roSGNode", "StandardMessageDialog")
    d.title = title
    d.message = [text]
    d.buttons = ["OK"]
    d.observeField("buttonSelected", "closeDialog")
    m.top.dialog = d
end sub

sub closeDialog()
    if m.top.dialog <> invalid then m.top.dialog.close = true
    focusCurrent()
end sub

' Regresa el control a la pantalla que se está viendo (después de cerrar una ventana).
sub focusCurrent()
    if m.video.visible then
        m.video.setFocus(true)
    else if m.current = "profiles" then
        m.profilesView.takeFocus = true
    else if m.current = "home" then
        m.homeView.takeFocus = true
    else if m.current = "detail" then
        m.detailView.takeFocus = true
    end if
end sub

' ---------- Acciones de Inicio y Ficha ----------

sub onHomeAction()
    a = m.homeView.action
    if a.type = "open" then
        m.detailView.item = a.item
        showScreen("detail")
    else if a.type = "live" then
        playLive(a.channels, a.index)
    else if a.type = "resume" then
        e = a.entry
        if e.kind = "movie" then
            playVod({ type: "play", kind: "movie", id: e.id, ext: ToStr(e.ext), title: e.title, poster: e.poster, start: e.pos })
        else
            ' Series: abrir la ficha, ahí está «Continuar» con el episodio correcto.
            m.detailView.item = { kind: "series", id: e.id, title: e.title, poster: e.poster, ext: "", plot: "" }
            showScreen("detail")
        end if
    end if
end sub

sub onDetailAction()
    a = m.detailView.action
    if a.type = "play" then playVod(a)
end sub

' ---------- Reproductor ----------

sub playVod(a as object)
    m.live = invalid
    m.playing = a
    if a.kind = "movie" then
        url = StreamUrl("movie", a.id, ToStr(a.ext))
        fmt = StreamFormatFor("movie", ToStr(a.ext))
        title = a.title
    else
        ep = a.episodes[a.index]
        url = StreamUrl("episode", ep.id, ep.ext)
        fmt = StreamFormatFor("episode", ep.ext)
        title = a.seriesTitle + "  ·  T" + ep.season.ToStr() + ":E" + ep.num.ToStr() + "  " + ep.title
    end if
    startVideo(url, fmt, title, a.start, false)
    saveResume(false, true)
end sub

sub playLive(channels as object, index as integer)
    m.playing = invalid
    m.live = { channels: channels, index: index }
    tune(index)
end sub

sub tune(index as integer)
    n = m.live.channels.Count()
    if n = 0 then return
    index = ((index mod n) + n) mod n
    m.live.index = index
    ch = m.live.channels[index]
    startVideo(StreamUrl("live", ch.id, ""), "hls", ch.title, 0, true)
    showOsd((index + 1).ToStr() + "   " + ch.title + "        ▲ ▼ cambiar de canal")
end sub

sub startVideo(url as string, fmt as string, title as string, start as dynamic, isLive as boolean)
    c = CreateObject("roSGNode", "ContentNode")
    c.url = url
    c.streamFormat = fmt
    c.title = title
    c.live = isLive
    if start <> invalid and start > 0 then c.PlayStart = start
    m.video.content = c
    m.video.visible = true
    m.video.control = "play"
    m.video.setFocus(true)
    m.lastSave = 0
end sub

sub stopVideo()
    saveResume(false, false)
    m.video.control = "stop"
    m.video.visible = false
    m.playing = invalid
    m.live = invalid
    hideOsd()
    m.homeView.refreshMine = true
    if m.current = "detail" then m.detailView.refreshButtons = true
    showScreen(m.current)
end sub

sub onVideoState()
    state = m.video.state
    if state = "finished" then
        if m.playing <> invalid then
            saveResume(true, false)
            a = m.playing
            if a.kind = "episode" and a.index + 1 < a.episodes.Count() then
                ' Siguiente episodio automático.
                a.index = a.index + 1
                a.start = 0
                playVod(a)
                showOsd("Siguiente episodio")
                return
            end if
        end if
        stopVideo()
    else if state = "error" then
        showOsd("No se pudo reproducir: " + ToStr(m.video.errorMsg))
    end if
end sub

sub onVideoPosition()
    if m.playing = invalid then return
    curPos = m.video.position
    if curPos - m.lastSave >= 10 then
        m.lastSave = curPos
        saveResume(false, false)
    end if
end sub

' Guarda el avance en «Seguir viendo» del perfil.
sub saveResume(finished as boolean, atStart as boolean)
    a = m.playing
    if a = invalid or m.profile = invalid then return
    pid = m.profile.id
    curPos = Int(m.video.position)
    dur = Int(m.video.duration)
    if atStart then curPos = Int(ToStr(a.start).ToFloat())
    if dur > 0 and curPos > dur * 0.95 then finished = true
    if not atStart and not finished and curPos < 10 then return
    if a.kind = "movie" then
        key = "movie:" + a.id
        if finished then
            Resume_Remove(pid, key)
        else
            Resume_Save(pid, { k: key, kind: "movie", id: a.id, title: a.title, poster: a.poster, ext: ToStr(a.ext), pos: curPos, dur: dur })
        end if
        return
    end if
    key = "series:" + a.seriesId
    ep = a.episodes[a.index]
    if finished then
        if a.index + 1 < a.episodes.Count() then
            nxt = a.episodes[a.index + 1]
            Resume_Save(pid, { k: key, kind: "series", id: a.seriesId, title: a.seriesTitle, poster: a.poster, ep: nxt.id, epLabel: "T" + nxt.season.ToStr() + ":E" + nxt.num.ToStr(), pos: 0, dur: 0 })
        else
            Resume_Remove(pid, key)
        end if
    else
        Resume_Save(pid, { k: key, kind: "series", id: a.seriesId, title: a.seriesTitle, poster: a.poster, ep: ep.id, epLabel: "T" + ep.season.ToStr() + ":E" + ep.num.ToStr(), pos: curPos, dur: dur })
    end if
end sub

sub showOsd(text as string)
    m.osdText.text = text
    m.osd.visible = true
    m.osdTimer.control = "stop"
    m.osdTimer.control = "start"
end sub

sub hideOsd()
    m.osd.visible = false
end sub

' ---------- Control remoto ----------

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if m.video.visible then
        if key = "back" then
            stopVideo()
            return true
        end if
        if m.live <> invalid then
            if key = "up" then
                tune(m.live.index - 1)
                return true
            else if key = "down" then
                tune(m.live.index + 1)
                return true
            else if key = "OK" or key = "info" then
                ch = m.live.channels[m.live.index]
                showOsd((m.live.index + 1).ToStr() + "   " + ch.title)
                return true
            end if
        end if
        return false
    end if
    if key <> "back" and m.top.dialog = invalid then
        focusCurrent()
        return true
    end if
    if key = "back" then
        if m.current = "detail" then
            m.homeView.refreshMine = true
            showScreen("home")
            return true
        else if m.current = "home" then
            showScreen("profiles")
            return true
        end if
    end if
    return false
end function
