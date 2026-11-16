sub init()
    m.top.functionName = "fetchData"
end sub

' request: { id, kind: "json" | "items", url, itemKind: "live"|"movie"|"series", sort, kids }
sub fetchData()
    req = m.top.request
    relaxed = (Store_Get("tls_relaxed", "0") = "1")
    attempts = []
    usedHttp = false
    usedRelaxed = relaxed
    r = HttpGet(req.url, relaxed)
    attempts.Push("https " + ToStr(r.code) + " " + ToStr(r.reason))
    isHttps = LCase(Left(req.url, 8)) = "https://"
    ' 1) Si https falla y no fue por el servidor (código negativo = certificado/conexión),
    '    reintentar sin revisar el certificado (el Roku es más estricto que Android).
    if r.body = invalid and isHttps and not relaxed and r.code < 0 then
        r2 = HttpGet(req.url, true)
        attempts.Push("https sin verificar " + ToStr(r2.code) + " " + ToStr(r2.reason))
        if r2.body <> invalid then
            r = r2
            usedRelaxed = true
        end if
    end if
    ' 2) Último intento: http.
    if r.body = invalid and isHttps then
        r3 = HttpGet("http://" + Mid(req.url, 9), false)
        attempts.Push("http " + ToStr(r3.code) + " " + ToStr(r3.reason))
        if r3.body <> invalid then
            r = r3
            usedHttp = true
        end if
    end if
    data = invalid
    if r.body <> invalid and r.body <> "" then data = ParseJson(r.body)
    res = {
        id: req.id, kind: req.kind, ok: data <> invalid, code: r.code, reason: r.reason,
        usedHttp: usedHttp, usedRelaxed: usedRelaxed, attempts: attempts.Join(" | ")
    }
    if req.kind = "items" then
        res.content = BuildItems(data, req.itemKind)
    else
        res.data = data
    end if
    m.top.response = res
end sub

' Regresa { body (o invalid), code, reason } para poder explicar los errores.
' relaxed = no revisar el certificado del servidor (la conexión sigue cifrada).
function HttpGet(url as string, relaxed as boolean) as object
    xfer = CreateObject("roUrlTransfer")
    xfer.SetCertificatesFile("common:/certs/ca-bundle.crt")
    xfer.InitClientCertificates()
    if relaxed then
        xfer.EnablePeerVerification(false)
        xfer.EnableHostVerification(false)
    end if
    xfer.EnableEncodings(true)
    xfer.RetainBodyOnError(true)
    xfer.AddHeader("User-Agent", "MuGaTV-Roku/1.0")
    xfer.SetUrl(url)
    port = CreateObject("roMessagePort")
    xfer.SetMessagePort(port)
    if not xfer.AsyncGetToString() then return { body: invalid, code: -1, reason: "no se pudo iniciar" }
    msg = wait(25000, port)
    if type(msg) = "roUrlEvent" then
        code = msg.GetResponseCode()
        if code = 200 then return { body: msg.GetString(), code: code, reason: "" }
        return { body: invalid, code: code, reason: msg.GetFailureReason() }
    end if
    xfer.AsyncCancel()
    return { body: invalid, code: -28, reason: "sin respuesta" }
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
