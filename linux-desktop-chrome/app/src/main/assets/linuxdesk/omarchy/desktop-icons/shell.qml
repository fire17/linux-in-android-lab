// LinuxDesk: desktop icons for the Hyprland desktop (sweep lane 2026-11-20, notes/gap/SWEEP.md). A tiny separate
// Quickshell config (`qs -p /opt/linuxdesk/omarchy/desktop-icons`): one column of ~/Desktop entries on the Bottom layer,
// above Omarchy's wallpaper and below every window. Only the icon strip takes input; the rest of the screen stays the
// wallpaper's. One tap opens (a tap is a click on the phone). Data and launching: ../desktop-icons.sh.
// NOT pcmanfm-qt --desktop (it took the session down at 06:47).
import QtQuick
import Quickshell
import Quickshell.Io
import Quickshell.Wayland
import Quickshell.Widgets

ShellRoot {
  id: root

  readonly property string helper: "/opt/linuxdesk/omarchy/desktop-icons.sh"
  property var entries: []

  function reload() {
    if (!lister.running) lister.running = true
  }

  function iconSource(icon) {
    // Same lookup as Omarchy's AppLibrary.qml: a path, else the icon theme, else the generic app icon.
    if (icon.indexOf("/") === 0) return "file://" + icon
    var themed = Quickshell.iconPath(icon, true)
    if (themed.length > 0) return themed
    return Quickshell.iconPath("application-x-executable", true)
  }

  Process {
    id: seeder
    command: ["sh", root.helper, "seed"]
    running: true
    onExited: root.reload()
  }

  Process {
    id: lister
    command: ["sh", root.helper, "list"]
    stdout: StdioCollector {
      waitForEnd: true
      onStreamFinished: {
        var out = []
        var lines = text.split("\n")
        for (var i = 0; i < lines.length; i++) {
          var p = lines[i].split("\t")
          if (p.length >= 3 && p[0].length > 0) out.push({ path: p[0], name: p[1], icon: p[2] })
        }
        root.entries = out
      }
    }
  }

  // ~/Desktop changes (a file dropped there from Thunar) show up within 5 s.
  Timer {
    interval: 5000
    running: true
    repeat: true
    onTriggered: root.reload()
  }

  Variants {
    model: Quickshell.screens

    PanelWindow {
      required property var modelData
      screen: modelData

      anchors { top: true; left: true; bottom: true }
      implicitWidth: 132
      color: "transparent"
      // Reserve no space (tiled windows keep the full width); the column starts below the bar via topMargin.
      exclusionMode: ExclusionMode.Ignore
      WlrLayershell.layer: WlrLayer.Bottom
      WlrLayershell.namespace: "linuxdesk-desktop-icons"
      WlrLayershell.keyboardFocus: WlrKeyboardFocus.None

      Column {
        anchors.top: parent.top
        anchors.left: parent.left
        anchors.topMargin: 72
        anchors.leftMargin: 12
        spacing: 10

        Repeater {
          model: root.entries

          Item {
            required property var modelData
            width: 108
            height: 104

            Rectangle {
              anchors.fill: parent
              radius: 12
              color: area.pressed ? "#507aa2f7" : (area.containsMouse ? "#307aa2f7" : "transparent")
            }

            IconImage {
              id: icon
              anchors.horizontalCenter: parent.horizontalCenter
              anchors.top: parent.top
              anchors.topMargin: 6
              implicitSize: 56
              source: root.iconSource(modelData.icon)
            }

            Text {
              anchors.top: icon.bottom
              anchors.topMargin: 4
              anchors.horizontalCenter: parent.horizontalCenter
              width: parent.width - 6
              horizontalAlignment: Text.AlignHCenter
              elide: Text.ElideRight
              maximumLineCount: 2
              wrapMode: Text.Wrap
              text: modelData.name
              color: "#ffffff"
              style: Text.Outline
              styleColor: "#a0000000"
              font.pixelSize: 15
            }

            MouseArea {
              id: area
              anchors.fill: parent
              hoverEnabled: true
              onClicked: Quickshell.execDetached(["sh", root.helper, "open", modelData.path])
            }
          }
        }
      }
    }
  }
}
