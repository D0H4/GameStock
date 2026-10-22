package com.gamestock.backend.openapi;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Set;

/** Only explicit proxy IPs may supply X-Forwarded-For. Walk from the trusted end of the chain. */
final class TrustedClientAddress {
    private final Set<InetAddress> proxies=new HashSet<>();
    TrustedClientAddress(String configured) {
        for(String value:configured.split(","))if(!value.isBlank())proxies.add(literal(value.trim()));
    }
    String resolve(HttpServletRequest request) {
        String remote=request.getRemoteAddr();
        InetAddress peer;
        try {peer=literal(remote);}catch(IllegalArgumentException invalid){return remote;}
        if(!proxies.contains(peer))return remote;
        String forwarded=request.getHeader("X-Forwarded-For");
        if(forwarded==null||forwarded.length()>1024)return remote;
        String[] chain=forwarded.split(",",-1);
        if(chain.length>16)return remote;
        for(int i=chain.length-1;i>=0 && proxies.contains(peer);i--) {
            try {peer=literal(chain[i].trim());}catch(IllegalArgumentException invalid){return remote;}
        }
        return peer.getHostAddress();
    }
    private static InetAddress literal(String value) {
        if(value==null||!value.matches("[0-9a-fA-F:.]+")||!value.contains(":")&&!value.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))
            throw new IllegalArgumentException("Proxy/client address must be an IP literal");
        try {return InetAddress.getByName(value);}catch(Exception invalid){throw new IllegalArgumentException("Invalid IP literal",invalid);}
    }
}
