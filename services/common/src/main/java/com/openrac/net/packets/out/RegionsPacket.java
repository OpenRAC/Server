package com.openrac.net.packets.out;

import com.openrac.net.packets.Packets;
import com.openrac.net.packets.model.OutgoingPacket;
import com.openrac.net.packets.model.OutputBuffer;

/**
 * The hub's list of regions: count byte, then each region's name, host, port and players. An empty
 * host means the address the client reached the hub on.
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
     * @param regions Each region as {@code Region.serialize()} writes it.
     */

    public RegionsPacket(int count, OutputBuffer regions) {
        super(Packets.REGIONS.ordinal());

        writeUnsignedByte(count);
        append(regions);
    }
}
