' Descargas por internet (solo desde tareas en segundo plano, nunca desde la pantalla).

function SchemeLabel(url as string, relaxed as boolean) as string
    if LCase(Left(url, 8)) = "https://" then
        if relaxed then return "https sin verificar"
        return "https"
    end if
    return "http"
end function

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

