sub init()
    m.top.functionName = "fetchData"
end sub

' request: { id, kind: "json" | "items", url, itemKind: "live"|"movie"|"series", sort, kids }
sub fetchData()
    req = m.top.request
    relaxed = (Store_Get("tls_relaxed", "0") = "1")
    attempts = []
    usedHttp = false
    usedHttps = false
    usedRelaxed = relaxed
    url = req.url
    isHttps = LCase(Left(url, 8)) = "https://"
    isHttp = LCase(Left(url, 7)) = "http://"
    r = HttpGet(url, relaxed)
    attempts.Push(SchemeLabel(url, relaxed) + " " + ToStr(r.code) + " " + ToStr(r.reason))
    if isHttps then
        ' Certificado rechazado (código negativo): reintentar sin revisarlo.
        if r.body = invalid and not relaxed and r.code < 0 then
            r2 = HttpGet(url, true)
            attempts.Push("https sin verificar " + ToStr(r2.code) + " " + ToStr(r2.reason))
            if r2.body <> invalid then
                r = r2
                usedRelaxed = true
            end if
        end if
        ' Último intento: http.
        if r.body = invalid then
            r3 = HttpGet("http://" + Mid(url, 9), false)
            attempts.Push("http " + ToStr(r3.code) + " " + ToStr(r3.reason))
            if r3.body <> invalid then
                r = r3
                usedHttp = true
            end if
        end if
    else if isHttp and r.body = invalid then
        ' El servidor está guardado con http:// pero el puerto es de https
        ' (responde «400 Bad request»): probar con https, normal y sin revisar certificado.
        su = "https://" + Mid(url, 8)
        r4 = HttpGet(su, false)
        attempts.Push("https " + ToStr(r4.code) + " " + ToStr(r4.reason))
        if r4.body = invalid and r4.code < 0 then
            r5 = HttpGet(su, true)
            attempts.Push("https sin verificar " + ToStr(r5.code) + " " + ToStr(r5.reason))
            if r5.body <> invalid then
                r4 = r5
                usedRelaxed = true
            end if
        end if
        if r4.body <> invalid then
            r = r4
            usedHttps = true
        end if
    end if
    data = invalid
    if r.body <> invalid and r.body <> "" then data = ParseJson(r.body)
    res = {
        id: req.id, kind: req.kind, ok: data <> invalid, code: r.code, reason: r.reason,
        usedHttp: usedHttp, usedHttps: usedHttps, usedRelaxed: usedRelaxed, attempts: attempts.Join(" | ")
    }
    if req.kind = "items" then
        res.content = BuildItems(data, req.itemKind)
    else
        res.data = data
    end if
    m.top.response = res
end sub

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
