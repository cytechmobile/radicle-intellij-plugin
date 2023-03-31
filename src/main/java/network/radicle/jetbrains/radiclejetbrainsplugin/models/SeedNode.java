package network.radicle.jetbrains.radiclejetbrainsplugin.models;

import network.radicle.jetbrains.radiclejetbrainsplugin.config.RadicleGlobalSettingsHandler;

import java.util.ArrayList;
import java.util.List;

public class SeedNode {
    public String host;
    public String port;

    public SeedNode(String host, String port) {
        this.host = host;
        this.port = port;
    }

    public static List<SeedNode> getNodesFromString(String[] nodes) {
        var seedNodes = new ArrayList<SeedNode>();
        for (var n : nodes) {
            var name = n.split("\\" + RadicleGlobalSettingsHandler.RAD_SEED_SEPERATOR)[0];
            var port = n.split("\\" + RadicleGlobalSettingsHandler.RAD_SEED_SEPERATOR)[1];
            seedNodes.add(new SeedNode(name, port));
        }
        return seedNodes;
    }

    @Override
    public String toString() {
        return host + RadicleGlobalSettingsHandler.RAD_SEED_SEPERATOR + port;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        return toString().equals(obj.toString());
    }

    @Override
    public int hashCode() {
        return toString().hashCode();
    }
}
