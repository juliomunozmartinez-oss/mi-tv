sub init()
    m.grid = m.top.findNode("grid")
    m.top.findNode("title").font = "font:LargeBoldSystemFont"
    m.grid.observeField("itemSelected", "onSelected")
    build()
end sub

sub build()
    m.profiles = Profiles_All()
    root = CreateObject("roSGNode", "ContentNode")
    for each p in m.profiles
        n = root.CreateChild("ContentNode")
        n.title = p.name
        n.HDPosterUrl = AvatarUri(p.avatar)
        if p.kids = true then n.shortDescriptionLine1 = "Infantil"
    end for
    s = root.CreateChild("ContentNode")
    s.title = "Ajustes"
    s.HDPosterUrl = "pkg:/images/settings.png"
    m.grid.content = root
    ' Centrar la fila según cuántos perfiles hay.
    count = m.profiles.Count() + 1
    m.grid.numColumns = count
    width = count * 220 + (count - 1) * 40
    m.grid.translation = [(1920 - width) / 2, 400]
end sub

sub focusGrid()
    m.grid.setFocus(true)
end sub

sub onSelected()
    i = m.grid.itemSelected
    if i < m.profiles.Count() then
        m.top.chosen = { type: "profile", profile: m.profiles[i] }
    else
        m.top.chosen = { type: "settings" }
    end if
end sub
