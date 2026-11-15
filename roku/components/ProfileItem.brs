sub init()
    m.avatar = m.top.findNode("avatar")
    m.name = m.top.findNode("name")
    m.badge = m.top.findNode("badge")
    m.name.font = "font:MediumBoldSystemFont"
    m.badge.font = "font:SmallSystemFont"
end sub

sub onContent()
    c = m.top.itemContent
    if c = invalid then return
    m.avatar.uri = c.HDPosterUrl
    m.name.text = c.title
    m.badge.text = c.shortDescriptionLine1
end sub

sub onFocus()
    if m.top.focusPercent > 0.5 then
        m.name.color = "0xFFFFFFFF"
    else
        m.name.color = "0x94A3B8FF"
    end if
end sub
