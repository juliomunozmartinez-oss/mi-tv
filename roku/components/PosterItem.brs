sub init()
    m.bg = m.top.findNode("bg")
    m.fallback = m.top.findNode("fallback")
    m.poster = m.top.findNode("poster")
    m.progressBg = m.top.findNode("progressBg")
    m.progress = m.top.findNode("progress")
    m.star = m.top.findNode("star")
end sub

sub onSize()
    w = m.top.width
    h = m.top.height
    m.bg.width = w
    m.bg.height = h
    m.poster.width = w
    m.poster.height = h
    m.fallback.width = w - 20
    m.fallback.height = h
    m.fallback.translation = [10, 0]
    m.progressBg.width = w
    m.progressBg.height = 6
    m.progressBg.translation = [0, h - 6]
    m.progress.height = 6
    m.progress.translation = [0, h - 6]
    m.star.translation = [w - 34, 4]
    updateProgress()
end sub

sub onContent()
    c = m.top.itemContent
    if c = invalid then return
    m.fallback.text = c.title
    m.poster.uri = c.HDPosterUrl
    if FldStr(c, "itemKind") = "live" then
        m.poster.loadDisplayMode = "scaleToFit"
    else
        m.poster.loadDisplayMode = "scaleToZoom"
    end if
    m.star.visible = (FldStr(c, "inList") = "1")
    updateProgress()
end sub

sub updateProgress()
    c = m.top.itemContent
    p = Fld(c, "progress")
    if p = invalid or p < 0 then
        m.progressBg.visible = false
        m.progress.visible = false
        return
    end if
    m.progressBg.visible = true
    m.progress.visible = true
    m.progress.width = m.top.width * p
end sub
