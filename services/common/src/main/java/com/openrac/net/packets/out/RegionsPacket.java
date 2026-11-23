package com.openrac.net.packets.out;

import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.OutgoingPacket;
import com.openrac.net.packets.model.OutputBuffer;

/**
 * The hub's list of regions: count byte, then each region's code, host, port and players. An empty
 * host means the address the client reached the hub on. Each server's name follows the list, in
 * its order, appended so that older clients stop reading before it.
 */

public class RegionsPacket extends OutgoingPacket {
    /**
     * Client to hub: which regions it may play in.
     * @param family The client's game family.
     */

    public RegionsPacket(String family) {
        super(Packets.REGIONS.ordinal());

        writeString(family);
    }

    /**
     * Hub to client: the regions.
     * @param count How many regions follow.
     * @param regions Each region as {@code GameServer.serializeRegion()} writes it.
     * @param names Each server's name as a string, in the same order.
     */

    public RegionsPacket(int count, OutputBuffer regions, OutputBuffer names) {
        super(Packets.REGIONS.ordinal());

        writeUnsignedByte(count);
        append(regions);
        append(names);
    }
}
