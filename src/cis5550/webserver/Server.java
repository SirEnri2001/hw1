package cis5550.webserver;
import cis5550.tools.Logger;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalField;
import java.util.*;
import java.util.concurrent.*;

class RequestHandler implements Callable<Object> {
    Socket socket;
    private final Logger logger;
    public RequestHandler(Socket socket, Logger logger){
        this.socket = socket;
        this.logger = logger;
    }

    @Override
    public Object call() throws Exception {
        doRequestWorker();
        return null;
    }

    public void onError(Socket socket, HttpException httpException) throws IOException {
        PrintWriter printWriter = new PrintWriter(socket.getOutputStream());
        printWriter.println(
                String.format("HTTP/1.1 %1$s %2$s\r\n\r\n",
                        httpException.errCode,
                        httpException.description));
        printWriter.flush();
    }

    public void acceptIncoming() throws IOException, HttpException{
        boolean isEOF = false;
        boolean firstHeader = true;
        while(!isEOF){
            logger.info("Incoming connection from: "+socket.getRemoteSocketAddress());
            HashMap<String, String> headerMap = getHeaderMap(socket, firstHeader);
            firstHeader = false;
            logger.info("Header retrieve success");
            switch(headerMap.get("Method").toUpperCase()){
                case "GET":
                    break;
                case "HEAD":
                case "POST":
                case "PUT":
                case "DELETE":
                case "CONNECT":
                case "OPTION":
                case "TRACE":
                case "PATCH":
                    throw new HttpException(405, "Method Not Allowed");
                default:
                    throw new HttpException(501, "Not Implemented");
            }
            if(!headerMap.get("Version").equalsIgnoreCase("HTTP/1.1")){
                throw new HttpException(505, "Version Not Supported");
            }
            validateHeaderMap(headerMap);
            if(headerMap.get("Connection")!=null && headerMap.get("Connection").equalsIgnoreCase("close")){
                isEOF = true;
            }
            int[] bodyString = null;
            if(headerMap.get("Content-Length")!=null){
                logger.info("Body retrieve: content length "+headerMap.get("Content-Length"));
                bodyString = getBodyString(socket, Integer.parseInt(headerMap.get("Content-Length")));
                logger.info("Body retrieve success");
            }
            PrintWriter printWriter = new PrintWriter(socket.getOutputStream());
            if(headerMap.get("If-Modified-Since")!=null && headerMap.get("Uri")!=null){
                Path file = Paths.get("./test/" + headerMap.get("Uri"));
                BasicFileAttributes attr = Files.readAttributes(file, BasicFileAttributes.class);
                FileTime fileTime = attr.lastModifiedTime();
                System.out.println("lastModifiedTime: " + fileTime);
                DateTimeFormatter formatter = DateTimeFormatter.RFC_1123_DATE_TIME;
                TemporalAccessor ret = null;
                try{
                    ret = formatter.parse(headerMap.get("If-Modified-Since"));
                }catch(DateTimeParseException e){
                    throw new HttpException(400, "Bad request: Datetime in If-Modified-Since is Invalid");
                }
                System.out.println("Header: " + headerMap.get("If-Modified-Since"));
                try{
                    long requestedSince = (ret.getLong(ChronoField.EPOCH_DAY) *24*60*60+ret.getLong(ChronoField.OFFSET_SECONDS))*1000;
                    long fileModifiedSince = fileTime.toMillis();

                    if(requestedSince>fileModifiedSince){
                        logger.info("Not modified");
                        printWriter.println("HTTP/1.1 304 Not Modified");
                        printWriter.println("");
                        printWriter.flush();
                        continue;
                    }
                }catch (Exception e){
                    e.printStackTrace();
                }

            }

            try{
                printWriter.println("HTTP/1.1 200 OK");
                byte[] fileContent = getResponseContent(
                        headerMap.get("Uri"),
                        0, -1
                );
                printWriter.println(String.format("Content-Length: %1$s",fileContent.length));
                printWriter.println("");
                printWriter.flush();
                socket.getOutputStream().write(fileContent);
            }catch (FileNotFoundException fnf){
                throw new HttpException(404, "Not Found");
            }
            printWriter.flush();
            logger.info(String.format("Remote %1$s : %2$s %3$s",
                    socket.getRemoteSocketAddress(), 200, "OK"));
        }
    }

    public String readLine(Socket socket) throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        while(true){
            int readRes = socket.getInputStream().read();
            if (readRes==-1){
                throw new SocketException("Socket Closed On Remote");
            }
            stringBuilder.append((char)readRes);
            if(stringBuilder.charAt(stringBuilder.length()-1)=='\n'){
                return stringBuilder.toString().trim();
            }
        }
    }

    public LinkedList<String> getHeaderStringList(Socket socket) throws IOException {
        logger.info("Getting Header");
        LinkedList<String> headerStringArray = new LinkedList<>();
        String line = readLine(socket);
        while(!line.isEmpty()){
            headerStringArray.add(line);
            line = readLine(socket);
        }
        return headerStringArray;
    }

    public HashMap<String, String> getHeaderMap(Socket socket, boolean blockWaiting) throws HttpException, IOException {
        LinkedList<String> headerStringList = getHeaderStringList(socket);
        logger.info("Parsing Header");
        HashMap<String, String> retMap = new HashMap<>();
        boolean isMethodLine = true;
        for(String line : headerStringList){
            if(isMethodLine){
                try{
                    String[] methodUriVersion = line.split(" ");
                    retMap.put("Method", methodUriVersion[0].trim());
                    retMap.put("Uri", methodUriVersion[1].trim());
                    retMap.put("Version", methodUriVersion[2].trim());
                    logger.info("[Method] " + line);
                    if(line.indexOf(':')!=-1){
                        throw new HttpException(400, "Bad Request");
                    }
                }catch (ArrayIndexOutOfBoundsException e){
                    throw new HttpException(400, "Bad Request");
                }
                isMethodLine = false;
                continue;
            }
            logger.info("[Header] " + line);
            try{
                int separate = line.indexOf(":");
                String headerItem = line.substring(0,separate).trim();
                String headerValue = line.substring(separate+1).trim();
                retMap.put(headerItem, headerValue);
            }catch (ArrayIndexOutOfBoundsException e){
                throw new HttpException(400, "Bad Request");
            }
        }
        return retMap;
    }

    public int[] getBodyString(Socket socket, int contentLength) throws SocketException, IOException {
        int[] resBody = new int[contentLength];
        for(int i = 0;i<contentLength;i++){
            resBody[i] = socket.getInputStream().read();
        }
        return resBody;
    }

    public void validateHeaderMap(HashMap<String, String> headerMap) throws HttpException {
        String uri = headerMap.get("Uri");
        uri = uri.replace('\\','/');
        StringBuilder uriBuilder = new StringBuilder();
        ArrayList<String> uris = new ArrayList<>();
        for(String s : uri.split("/")){
            if(s.isEmpty()){
                continue;
            }
            if(s.equals("..")){
                if(uris.isEmpty()){
                    continue;
                }
                uris.removeLast();
            }
            uriBuilder.append(s);
        }
        for(String s : uris){
            uriBuilder.append(s);
        }
        headerMap.put("Uri", uriBuilder.toString());
    }



    public byte[] getResponseContent(String path, int offset, int length) throws IOException {
        if(path.isEmpty()){
            String respond = "Hello world";
            if(length < 0){
                return respond.substring(offset,
                                Math.min(offset+respond.length(), respond.length()))
                        .getBytes(StandardCharsets.UTF_8);
            }
            return respond.substring(offset,
                            Math.min(offset+length, respond.length()))
                    .getBytes(StandardCharsets.UTF_8);
        }
        FileInputStream fileInputStream = new FileInputStream("./test/" + path);
        fileInputStream.readNBytes(offset);
        if(length<0){
            return fileInputStream.readAllBytes();
        }
        return fileInputStream.readNBytes(length);
    }

    public void doRequestWorker() throws IOException{
        try{
            try{
                acceptIncoming();
            } catch (HttpException httpe){
                logger.info(String.format("Remote %1$s : %2$s %3$s",
                        socket.getRemoteSocketAddress(), httpe.errCode, httpe.description));
                httpe.printStackTrace();
                onError(socket, httpe);
            }
            socket.close();
        }catch (SocketException socketException){
            logger.info(String.format("Remote %1$s : %2$s",
                    socket.getRemoteSocketAddress(), socketException.getMessage()));
        }
        socket.close();
    }
}

public class Server {
    private final Logger logger = Logger.getLogger(Server.class);

    public static void main(String args[]) throws Exception {
        Server server = new Server();
        server.startServer(8000);
    }

    public void startServer(int port) throws IOException {
        ServerSocket ssock = null;
        int NUM_WORKERS = 100;
        ExecutorService executorService = Executors.newFixedThreadPool(NUM_WORKERS);
        try{
            ssock = new ServerSocket(port);
        }catch (IOException ioe){
            logger.error("FATAL cannot start server socket");
        }
        assert ssock != null;
        while(true){
            Socket socket = ssock.accept();
            executorService.submit(new RequestHandler(socket, logger));
        }
    }
}
