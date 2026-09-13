package net.elfradio.d31bootstrap;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;

final class TcpListenerState {
    private TcpListenerState() { }

    static boolean isListening(int port) {
        if (port < 1 || port > 65535) return false;
        for (String path : new String[]{"/proc/net/tcp", "/proc/net/tcp6"}) {
            try (Reader reader = new FileReader(path)) {
                if (containsListener(reader, port)) return true;
            } catch (IOException | SecurityException ignored) { }
        }
        return false;
    }

    static boolean containsListener(Reader input, int port) throws IOException {
        return containsState(input,port,"0A");
    }

    static boolean hasEstablishedClient(int port) {
        for(String path:new String[]{"/proc/net/tcp","/proc/net/tcp6"}) {
            try(Reader reader=new FileReader(path)){if(containsState(reader,port,"01"))return true;}
            catch(IOException | SecurityException ignored){}
        }
        return false;
    }

    static boolean containsState(Reader input,int port,String state)throws IOException {
        if (port < 1 || port > 65535) return false;
        BufferedReader reader = new BufferedReader(input);
        String line;
        while ((line = reader.readLine()) != null) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 4 || !state.equalsIgnoreCase(fields[3])) continue;
            int colon = fields[1].lastIndexOf(':');
            if (colon < 0) continue;
            try {
                if (Integer.parseInt(fields[1].substring(colon + 1), 16) == port) return true;
            } catch (NumberFormatException ignored) { }
        }
        return false;
    }
}
