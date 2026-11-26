package com.zalo.server.service;

import com.zalo.common.Packet;
import com.zalo.common.Protocol;
import com.zalo.server.net.ClientHandler;
import com.zalo.server.net.SessionManager;

/** Signaling Server cho P2P: trả {IP, Port} cổng lắng nghe cục bộ của đối phương (đặc tả 2.2, bước 2-3). */
public final class P2pSignalingService {
    private P2pSignalingService() {}

    public static void lookup(ClientHandler h, Packet p) {
        int target = p.intVal("targetUserId", -1);
        ClientHandler t = SessionManager.get(target);
        Packet r = Packet.of(Protocol.P2P_PEER_INFO).put("targetUserId", target);
        if (t == null || t.p2pPort() <= 0) {
            h.send(r.put("online", false));
        } else {
            h.send(r.put("online", true).put("ip", t.ip()).put("port", t.p2pPort()));
        }
    }
}
