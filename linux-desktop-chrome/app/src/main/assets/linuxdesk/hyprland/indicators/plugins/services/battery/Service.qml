import QtQuick
import Quickshell
import Quickshell.Io
import Quickshell.Services.UPower
import "BatteryModel.js" as BatteryModel

Item {
  id: root

  property var shell: null
  property string omarchyPath: Quickshell.env("OMARCHY_PATH")

  readonly property int batteryThreshold: 10
  readonly property bool prootMode: Quickshell.env("OMARCHY_PROOT") === "1"

  // LinuxDesk (PRoot): no system D-Bus, so no UPower/NetworkManager/BlueZ. The Android app writes the phone's state
  // to /tmp/linuxdesk-status/status.json (hw/StatusFeed.kt); watched, plus a 5 s reload (inotify may be off).
  property var androidStatus: ({})
  property string androidStatusText: ""
  FileView {
    id: androidStatusFile
    path: root.prootMode ? "/tmp/linuxdesk-status/status.json" : ""
    watchChanges: root.prootMode
    printErrors: false
    onFileChanged: reload()
    onLoaded: {
      var t = text()
      if (t === root.androidStatusText) return
      try { root.androidStatus = JSON.parse(t); root.androidStatusText = t } catch (e) {}
    }
  }
  Timer { interval: 5000; running: root.prootMode; repeat: true; onTriggered: androidStatusFile.reload() }
  // UPower.displayDevice's shape, from the Android feed (PRoot only).
  readonly property var androidBattery: {
    var b = root.androidStatus.battery
    if (!b) return null
    var state = b.full ? UPowerDeviceState.FullyCharged
      : (b.charging ? UPowerDeviceState.Charging : (b.plugged ? UPowerDeviceState.PendingCharge : UPowerDeviceState.Discharging))
    return { isPresent: b.present === true, percentage: Math.max(0, Math.min(1, Number(b.percent || 0))), state: state,
             changeRate: b.charging ? 10 : 0, timeToFull: 0 }
  }
  readonly property var batteryDevice: prootMode ? androidBattery : UPower.displayDevice
  readonly property bool powerOnBattery: prootMode
    ? !!(root.androidStatus.battery && root.androidStatus.battery.present && !root.androidStatus.battery.plugged)
    : UPower.onBattery
  property string pendingPowerSource: ""

  PersistentProperties {
    id: persisted
    reloadableId: "omarchy-battery"
    property bool notifiedLowBattery: false
  }

  function batteryPercentage() {
    return BatteryModel.batteryPercentage(root.batteryDevice)
  }

  function isDischarging() {
    return BatteryModel.isDischarging(root.batteryDevice, root.powerOnBattery, UPowerDeviceState.Discharging)
  }

  function checkBattery() {
    var state = BatteryModel.shouldWarnLowBattery(root.batteryDevice, root.powerOnBattery, UPowerDeviceState.Discharging, batteryThreshold, persisted.notifiedLowBattery)
    persisted.notifiedLowBattery = state.notifiedLowBattery
    if (state.notify) sendLowBatteryWarning(state.level)
  }

  function sendLowBatteryWarning(level) {
    if (warningProcess.running) return
    warningProcess.command = [
      "omarchy-battery-low",
      String(level)
    ]
    warningProcess.running = true
  }

  function applyPowerProfile() {
    pendingPowerSource = root.powerOnBattery ? "battery" : "ac"
    if (!powerProfileProcess.running) runPendingPowerProfile()
  }

  function runPendingPowerProfile() {
    powerProfileProcess.command = ["omarchy-powerprofiles-set", pendingPowerSource]
    pendingPowerSource = ""
    powerProfileProcess.running = true
  }

  Process { id: warningProcess }

  Process {
    id: powerProfileProcess
    onExited: if (root.pendingPowerSource !== "") root.runPendingPowerProfile()
  }

  Timer {
    interval: 30000
    running: true
    repeat: true
    triggeredOnStart: true
    onTriggered: root.checkBattery()
  }

  // PRoot: plug/unplug arrives through the feed instead of UPower (no power profiles to switch there).
  onPowerOnBatteryChanged: if (root.prootMode) root.checkBattery()

  Connections {
    target: UPower
    enabled: !root.prootMode
    function onOnBatteryChanged() {
      root.checkBattery()
      root.applyPowerProfile()
    }
  }
}
