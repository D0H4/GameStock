package com.gamestock.backend.market;

import java.net.InetAddress;

/** Addresses unsuitable for a publisher fetch, including ranges not covered by isSiteLocalAddress. */
final class PublicNetworkAddress {
    private PublicNetworkAddress() { }
    static boolean allowed(InetAddress address) {
        if(address.isLoopbackAddress()||address.isSiteLocalAddress()||address.isLinkLocalAddress()
                ||address.isAnyLocalAddress()||address.isMulticastAddress()) return false;
        byte[] bytes=address.getAddress();
        int a=bytes[0]&255,b=bytes[1]&255;
        if(bytes.length==4) {
            int c=bytes[2]&255;
            return !(a==0||a==10||a==127||a>=224||a==100&&b>=64&&b<=127
                    ||a==169&&b==254||a==172&&b>=16&&b<=31||a==192&&b==168
                    ||a==192&&b==0||a==198&&(b==18||b==19)||a==198&&b==51&&c==100||a==203&&b==0&&c==113);
        }
        // Accept global unicast only; ULA, mapped/NAT64, link-local and multicast are excluded.
        if(bytes.length!=16 || (a&0xe0)!=0x20) return false;
        if(a==0x20&&b==0x02) return false; // 6to4 can embed an internal IPv4 destination.
        if(a==0x20&&b==0x01) {
            int c=bytes[2]&255,d=bytes[3]&255;
            if(c<2 || c==0x0d&&d==0xb8) return false; // special-purpose /23 and documentation /32
        }
        return true;
    }
}
