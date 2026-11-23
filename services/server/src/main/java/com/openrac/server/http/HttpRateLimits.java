package com.openrac.server.http;

import com.openrac.server.Constants;
import com.openrac.net.limits.RateLimit;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The HTTP side-port's rates, per address: requests arrive on any worker, unlike a game connection's
 * own buckets, so this locks. The table forgets the address unseen longest at its size limit.
 */

public class HttpRateLimits {
    private static final int MAX_ADDRESSES = 10_000;

    private final Map<InetAddress, Buckets> buckets = new LinkedHashMap<>(16, 0.75f, true) {
        /**
         * @param eldest The address unseen longest.
         * @return Whether the table is past its size limit, so it goes.
         */

        @Override
        protected boolean removeEldestEntry(Map.Entry<InetAddress, Buckets> eldest) {
            return size() > MAX_ADDRESSES;
        }
    };

    /**
     * @param address The sender.
     * @return Whether it may store a set now.
     */

    public synchronized boolean takePut(InetAddress address) {
        return bucketsOf(address).puts.take();
    }

    /**
     * @param address The sender.
     * @return Whether it may fetch a set now.
     */

    public synchronized boolean takeGet(InetAddress address) {
        return bucketsOf(address).gets.take();
    }

    /**
     * @param address The sender.
     * @return Its buckets, full the first time it is seen.
     */

    private Buckets bucketsOf(InetAddress address) {
        return buckets.computeIfAbsent(address, key -> new Buckets(
                new RateLimit(Constants.MODSET_PUTS_PER_MINUTE, Constants.MODSET_PUTS_PER_MINUTE / 60d),
                new RateLimit(Constants.MODSET_GETS_PER_MINUTE, Constants.MODSET_GETS_PER_MINUTE / 60d)));
    }

    /**
     * An address's two rates.
     * @param puts The rate of stored sets.
     * @param gets The rate of fetched sets.
     */

    private record Buckets(RateLimit puts, RateLimit gets) {}
}
