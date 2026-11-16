sub init()
    m.top.functionName = "fetchData"
end sub

' request: { id, kind: "json" | "items", url, itemKind: "live"|"movie"|"series", sort, kids }
sub fetchData()
    req = m.top.request
    r = HttpGet(req.url)
    usedHttp = false
    ' Si https falla (certificado o conexión), intentar con http.
    if r.body = invalid and LCase(Left(req.url, 8)) = "https://" then
        r2 = HttpGet("http://" + Mid(req.url, 9))
        if r2.body <> invalid then
            r = r2
            usedHttp = true
        end if
    end if
    data = invalid
    if r.body <> invalid and r.body <> "" then data = ParseJson(r.body)
    res = { id: req.id, kind: req.kind, ok: data <> invalid, code: r.code, reason: r.reason, usedHttp: usedHttp }
    if req.kind = "items" then
        res.content = BuildItems(data, req.itemKind)
    else
        res.data = data
    end if
    m.top.response = res
end sub

' Regresa { body (o invalid), code, reason } para poder explicar los errores.
function HttpGet(url as string) as object
    xfer = CreateObject("roUrlTransfer")
    xfer.SetCertificatesFile("common:/certs/ca-bundle.crt")
    xfer.InitClientCertificates()
    xfer.EnableEncodings(true)
    xfer.RetainBodyOnError(true)
    xfer.AddHeader("User-Agent", "MuGaTV-Roku/1.0")
    xfer.SetUrl(url)
    port = CreateObject("roMessagePort")
    xfer.SetMessagePort(port)
    if not xfer.AsyncGetToString() then return { body: invalid, code: -1, reason: "no se pudo iniciar la conexión" }
    msg = wait(30000, port)
    if type(msg) = "roUrlEvent" then
        code = msg.GetResponseCode()
        if code = 200 then return { body: msg.GetString(), code: code, reason: "" }
        return { body: invalid, code: code, reason: msg.GetFailureReason() }
    end if
    xfer.AsyncCancel()
    return { body: invalid, code: -28, reason: "el servidor no respondió a tiempo" }
end function

' Convierte la lista del servidor en tarjetas (ContentNode) para las filas.
function BuildItems(data as dynamic, itemKind as string) as object
    root = CreateObject("roSGNode", "ContentNode")
    if type(data) <> "roArray" then return root
    ' Películas: las más nuevas primero. Series: las actualizadas más recientemente primero.
    if itemKind = "movie" then data.SortBy("added", "r")
    if itemKind = "series" then data.SortBy("last_modified", "r")
    for each item in data
        node = root.CreateChild("ContentNode")
        if itemKind = "series" then
            node.id = ToStr(item.series_id)
            node.HDPosterUrl = ToStr(item.cover)
            node.description = ToStr(item.plot)
        else
            node.id = ToStr(item.stream_id)
            node.HDPosterUrl = ToStr(item.stream_icon)
        end if
        node.title = ToStr(item.name)
        node.addFields({
            itemKind: itemKind,
            ext: ToStr(item.container_extension),
            rating: ToStr(item.rating)
        })
    end for
    return root
end function
