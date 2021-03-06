package net.doridian.amplifi;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.doridian.amplifi.packets.PacketEncap;
import net.doridian.amplifi.packets.PacketPayload;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;

public class WebSocket extends WebSocketClient {
    public static boolean MESSAGE_DEBUG = false;

    public interface IResponder {
        void gotResponse(String iface, String method, JsonObject payload, String msgpack);
    }

    private final HashMap<Integer, IResponder> responders = new HashMap<>();

    public WebSocket(String ip) throws URISyntaxException {
        super(new URI("wss://" + ip + ":9016/"));
        this.setMySocket();
    }


    public void sendCommand(String iface, String method, Object payload, IResponder responder) {
        PacketEncap command = PacketEncap.makeCommand(iface, method, new PacketPayload<>(payload));
        synchronized (responders) {
            responders.put(command.seqId, responder);
        }
        this.send(command.encode());
    }

    private class CommandSender implements IResponder {
        private JsonObject jsonObject = null;
        private String msgpackRes = null;
        private boolean gotReply = false;
        private Thread waitThread;

        CommandSender(String iface, String method, Object payload) {
            waitThread = Thread.currentThread();
            sendCommand(iface, method, payload, this);
        }

        private void waitForResponse() throws InterruptedException {
            if (Thread.currentThread() != waitThread) {
                throw new RuntimeException("Wrong thread");
            }

            while (!gotReply) {
                synchronized (waitThread) {
                    waitThread.wait(5000);
                }
            }
        }

        @Override
        public void gotResponse(String iface, String method, JsonObject payload, String msgpack) {
            jsonObject = payload;
            msgpackRes = msgpack;
            gotReply = true;
            synchronized (waitThread) {
                waitThread.notify();
            }
        }
    }

    public JsonObject sendCommandJSONSync(String iface, String method, Object payload) throws InterruptedException {
        CommandSender sender = new CommandSender(iface, method, payload);
        sender.waitForResponse();
        return sender.jsonObject;
    }

    public void sendCommandSync(String iface, String method, Object payload) throws InterruptedException {
        CommandSender sender = new CommandSender(iface, method, payload);
        sender.waitForResponse();
    }

    public String sendCommandMsgpackSync(String iface, String method, Object payload) throws InterruptedException {
        CommandSender sender = new CommandSender(iface, method, payload);
        sender.waitForResponse();
        return sender.msgpackRes;
    }

    private void setMySocket() {
        try {
            this.setSocket(Utils.getAllTrustFactory().createSocket());
        } catch(IOException io) {
            throw new RuntimeException(io);
        }
    }

    public void onOpen(ServerHandshake serverHandshake) {

    }

    public void onMessage(String s) {
        if (MESSAGE_DEBUG) {
            System.out.println(s);
        }

        Gson gson = new Gson();
        Type packetType = new TypeToken<PacketEncap<JsonObject>>() { }.getType();
        PacketEncap<JsonObject> packet = gson.fromJson(s, packetType);

        if (!packet.type.equals("response")) {
            return;
        }

        IResponder responder;
        synchronized (responders) {
            responder = responders.remove(packet.seqId);
        }
        if (responder != null) {
            try {
                responder.gotResponse(packet.iface, packet.method, packet.payload.value, packet.payload.msgpack);
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    public void onClose(int i, String s, boolean b) {

    }

    public void onError(Exception e) {

    }
}
