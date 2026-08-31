# Process Manager

The **Process Manager** provides real-time hardware telemetry and comprehensive process inspection for connected Android devices. Powered by the lightweight **DioxAgent** daemon, it streams live memory, CPU utilization, and running process states with low overhead.

---

## Features & Telemetry

### 1. Live Memory & CPU Stats Cards
- **System Memory**: Displays total RAM, currently used RAM, free RAM, and a real-time progress bar with the overall percentage used.
- **CPU Usage**: Displays total system CPU utilization percentage along with total CPU core count (e.g. `12.5% · 8 Cores`).
- **Collapsible Cards**: Tap the arrow button in the top action bar to expand or collapse the RAM and CPU progress bars to maximize vertical screen space for process browsing.

### 2. Live Process List & Badges
For each active process on the target device, the Process Manager displays:
- **Application Icon**: Extracted directly from installed APK packages.
- **Process Name & Package Name**: Full identifier for the process and its parent package.
- **RAM Usage**: Formatted memory usage in MB or GB.
- **CPU Percentage**: Real-time normalized CPU percentage used by the process.
- **Process Metadata Badges**:
  - `PID`: Linux process ID.
  - `UID`: Android user/app identifier.
  - `Threads`: Total thread count spawned by the process.
  - `System` / `App`: Categorical classification badge.

---

## Filtering and Sorting

### Filters
- **All**: Shows all running user applications and system processes.
- **Apps**: Filters to third-party and user-installed applications.
- **System**: Filters to Android OS daemons and system services.

### Sorting Options
- **RAM (High to Low)**: Sort by highest memory consumption.
- **CPU (High to Low)**: Sort by highest real-time CPU usage.
- **PID (Low to High)**: Sort sequentially by process ID.
- **Name (A to Z)**: Sort alphabetically by process name.

### Real-Time Search
- Use the search bar at the top to filter the list dynamically by process name, package name, or numerical PID.

---

## Process Actions

Tap the three-dots menu on any process item to perform actions:

1. **Force Stop App** (`am force-stop`):
   - Immediately terminates all foreground activities, background services, alarms, and subprocesses associated with the parent app package.
2. **Kill PID** (`kill -9` / `SIGKILL`):
   - Sends a direct `SIGKILL` signal to terminate the specific process ID immediately without waiting for graceful shutdown.
