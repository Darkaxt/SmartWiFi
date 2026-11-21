package io.github.darkaxt.smartwifi;

// Runs inside the Shizuku user-service process with the shell identity (uid 2000).
interface IShellService {
    // Reserved by Shizuku for tearing down the user service.
    void destroy() = 16777114;

    // Runs an allow-listed `cmd wifi` read/scan subcommand. Returns "<exit>\n<stdout>".
    String wifiCommand(String subcommand) = 1;

    // Connects to a saved network by id through IWifiManager.connect as the shell identity.
    // Returns "success", "failure:<reason>", "timeout" or "error:<message>".
    String connectNetwork(int networkId) = 2;
}
