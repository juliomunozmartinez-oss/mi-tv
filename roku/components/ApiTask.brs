sub init()
    m.top.functionName = "fetchData"
end sub

' request: { id, kind: "json" | "items", url, itemKind: "live"|"movie"|"series", sort, kids }
sub fetchData()
    req = m.top.request
    text = HttpGet(req.url)
    data = invalid
    if text <> invalid and text <> "" then data = ParseJson(text)
    res = { id: req.id, kind: req.kind, ok: data <> invalid }
    if req.kind = "items" then
        res.content = BuildItems(data, req.itemKind)
    else
        res.data = data
    end if
    m.top.response = res
end sub

function HttpGet(url as string) as dynamic
    xfer = CreateObject("roUrlTransfer")
    xfer.SetCertificatesFile("common:/certs/ca-bundle.crt")
    xfer.InitClientCertificates()
    xfer.EnableEncodings(true)
    xfer.RetainBodyOnError(true)
    xfer.AddHeader("User-Agent", "MuGaTV-Roku/1.0")
    xfer.SetUrl(url)
    port = CreateObject("roMessagePort")
    xfer.SetMessagePort(port)
    if not xfer.AsyncGetToString() then return invalid
    msg = wait(45000, port)
    if type(msg) = "roUrlEvent" then
        if msg.GetResponseCode() = 200 then return msg.GetString()
        return invalid
    end if
    xfer.AsyncCancel()
    return invalid
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
