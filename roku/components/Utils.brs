' ---------- Guardado en el Roku (registro) ----------

function Store_Get(key as string, def as dynamic) as dynamic
    sec = CreateObject("roRegistrySection", "mugatv")
    if sec.Exists(key) then return sec.Read(key)
    return def
end function

sub Store_Set(key as string, value as string)
    sec = CreateObject("roRegistrySection", "mugatv")
    sec.Write(key, value)
    sec.Flush()
end sub

function Store_GetJson(key as string, def as dynamic) as dynamic
    raw = Store_Get(key, "")
    if raw = "" then return def
    data = ParseJson(raw)
    if data = invalid then return def
    return data
end function

sub Store_SetJson(key as string, value as dynamic)
    Store_Set(key, FormatJson(value))
end sub

' ---------- Cuenta del proveedor (Xtream) ----------

function Account_Get() as object
    return {
        server: Store_Get("server", ""),
        user: Store_Get("user", ""),
        pass: Store_Get("pass", "")
    }
end function

function Account_IsSet() as boolean
    a = Account_Get()
    return a.server <> "" and a.user <> "" and a.pass <> ""
end function

function NormalizeServer(raw as string) as string
    s = raw.Trim()
    while s.Len() > 0 and s.Right(1) = "/"
        s = s.Left(s.Len() - 1)
    end while
    low = LCase(s)
    if Left(low, 7) <> "http://" and Left(low, 8) <> "https://" then s = "http://" + s
    return s
end function

function Enc(s as string) as string
    return CreateObject("roUrlTransfer").Escape(s)
end function

function ApiUrl(action as string, extra as string) as string
    a = Account_Get()
    url = NormalizeServer(a.server) + "/player_api.php?username=" + Enc(a.user) + "&password=" + Enc(a.pass)
    if action <> "" then url = url + "&action=" + action
    return url + extra
end function

function StreamUrl(kind as string, id as string, ext as string) as string
    a = Account_Get()
    base = NormalizeServer(a.server)
    folder = "movie"
    if kind = "live" then folder = "live"
    if kind = "episode" then folder = "series"
    if kind = "live" then ext = "m3u8"
    if ext = "" then ext = "mp4"
    return base + "/" + folder + "/" + Enc(a.user) + "/" + Enc(a.pass) + "/" + id + "." + ext
end function

function StreamFormatFor(kind as string, ext as string) as string
    if kind = "live" then return "hls"
    e = LCase(ext)
    if e = "m3u8" then return "hls"
    if e = "mkv" then return "mkv"
    if e = "mov" then return "mov"
    return "mp4"
end function

' Saca servidor, usuario y contraseña de un enlace get.php o /playlist/usuario/contraseña/...
function ParseProviderLink(link as string) as dynamic
    s = link.Trim()
    r = CreateObject("roRegex", "^(https?://[^/]+)/.*[?&]username=([^&]+)&password=([^&]+)", "i")
    m1 = r.Match(s)
    if m1.Count() >= 4 then return { server: m1[1], user: m1[2], pass: m1[3] }
    r2 = CreateObject("roRegex", "^(https?://[^/]+)/(playlist|live|movie|series)/([^/]+)/([^/]+)", "i")
    m2 = r2.Match(s)
    if m2.Count() >= 5 then return { server: m2[1], user: m2[3], pass: m2[4] }
    return invalid
end function

' ---------- Varios ----------

function ToStr(v as dynamic) as string
    if v = invalid then return ""
    t = type(v)
    if t = "roString" or t = "String" then return v
    if t = "roInt" or t = "roInteger" or t = "Integer" or t = "LongInteger" or t = "roLongInteger" then return v.ToStr()
    if t = "roFloat" or t = "Float" or t = "Double" or t = "roDouble" then return Str(v).Trim()
    if t = "roBoolean" or t = "Boolean" then
        if v then return "1"
        return "0"
    end if
    return ""
end function

function IsKidsCategory(name as string) as boolean
    r = CreateObject("roRegex", "infantil|kids|ni(ñ|n)(o|a)s|animad|animaci|caricatura|cartoon|junior|nick|baby|peques|familiar", "i")
    return r.IsMatch(name)
end function

function IsMexicoCategory(name as string) as boolean
    r = CreateObject("roRegex", "(^|[^a-z])(mx|mex|m(e|é)xico|mexicanos?|azteca|televisa)([^a-z]|$)", "i")
    return r.IsMatch(name)
end function

' ---------- Perfiles ----------

function Profiles_All() as object
    list = Store_GetJson("profiles", invalid)
    if list = invalid or list.Count() = 0 then
        list = [
            { id: "jenny", name: "Jenny", kids: false, avatar: "rayo" },
            { id: "julio", name: "Julio", kids: false, avatar: "escudo" },
            { id: "cam", name: "Cam", kids: true, avatar: "estrella" },
            { id: "maju", name: "Maju", kids: true, avatar: "alas" }
        ]
    end if
    return list
end function

function AvatarUri(name as dynamic) as string
    if name = invalid or name = "" then name = "estrella"
    return "pkg:/images/hero_" + name + ".png"
end function

' ---------- Seguir viendo y Mi lista (por perfil) ----------

function Resume_All(pid as string) as object
    return Store_GetJson("resume_" + pid, [])
end function

sub Resume_Save(pid as string, entry as object)
    list = Resume_All(pid)
    out = [entry]
    for each e in list
        if e.k <> entry.k and out.Count() < 15 then out.Push(e)
    end for
    Store_SetJson("resume_" + pid, out)
end sub

sub Resume_Remove(pid as string, key as string)
    list = Resume_All(pid)
    out = []
    for each e in list
        if e.k <> key then out.Push(e)
    end for
    Store_SetJson("resume_" + pid, out)
end sub

function Resume_Find(pid as string, key as string) as dynamic
    for each e in Resume_All(pid)
        if e.k = key then return e
    end for
    return invalid
end function

function MyList_All(pid as string) as object
    return Store_GetJson("list_" + pid, [])
end function

function MyList_Has(pid as string, key as string) as boolean
    for each e in MyList_All(pid)
        if e.k = key then return true
    end for
    return false
end function

' Agrega o quita; regresa true si quedó en la lista.
function MyList_Toggle(pid as string, entry as object) as boolean
    list = MyList_All(pid)
    out = []
    found = false
    for each e in list
        if e.k = entry.k then
            found = true
        else
            out.Push(e)
        end if
    end for
    if not found then out.Unshift(entry)
    while out.Count() > 40
        out.Pop()
    end while
    Store_SetJson("list_" + pid, out)
    return not found
end function

' Lee un campo de un nodo sin fallar si no existe.
function Fld(node as dynamic, name as string) as dynamic
    if node = invalid then return invalid
    if node.hasField(name) then return node.getField(name)
    return invalid
end function

function FldStr(node as dynamic, name as string) as string
    return ToStr(Fld(node, name))
end function
