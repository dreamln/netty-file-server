package nayan.netty.server;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultFileRegion;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaders.isKeepAlive;
import static io.netty.handler.codec.http.HttpHeaders.setContentLength;
import io.netty.handler.codec.http.HttpMethod;

import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static io.netty.handler.codec.http.HttpResponseStatus.METHOD_NOT_ALLOWED;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import io.netty.handler.codec.http.HttpVersion;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import io.netty.handler.codec.http.LastHttpContent;

import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.DiskAttribute;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.util.CharsetUtil;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.activation.MimetypesFileTypeMap;
import org.json.JSONException;
import org.json.JSONObject;

/**
 *
 * @author nayan
 */
public class HttpStaticFileServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    // where to store the files
    private static final String BASE_PATH = "/Users/nayan/Reve/tmp/"; 
    
    // query param used to download a file
    private static final String FILE_QUERY_PARAM = "file";

    private HttpPostRequestDecoder decoder;
    private static final HttpDataFactory factory = new DefaultHttpDataFactory(true);

    private boolean readingChunks;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        // get the URL

        URI uri = new URI(request.getUri());
        String uriStr = uri.getPath();
        
        System.out.println(request.getMethod() + " request received");
        
        if (request.getMethod() == HttpMethod.GET) {
            serveFile(ctx, request); // user requested a file, serve it
        } else if (request.getMethod() == HttpMethod.POST) {
            uploadFile(ctx, request); // user requested to upload file, handle request
        } else if (request.getMethod() == HttpMethod.OPTIONS) {
            sendOptionsRequestResponse(ctx); // OPTIONS request received, send options header
        } else {
            // unknown request, send error message
            System.out.println(request.getMethod() + " request received, sending 405");
            sendError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED);
        }

    }

    private void serveFile(ChannelHandlerContext ctx, FullHttpRequest request) {

        // decode the query string
        QueryStringDecoder decoderQuery = new QueryStringDecoder(request.getUri());
        Map<String, List<String>> uriAttributes = decoderQuery.parameters();

        // get the requested file name
        String fileName = "";
        try {
            fileName = uriAttributes.get(FILE_QUERY_PARAM).get(0);
        } catch (Exception e) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, FILE_QUERY_PARAM + " query param not found");
            return;
        }

        // start serving the requested file
        sendFile(ctx, fileName, request);
    }

    /**
     * This method reads the requested file from disk and sends it as response.
     * It also sets proper content-type of the response header
     *
     * @param fileName name of the requested file
     */
    private void sendFile(ChannelHandlerContext ctx, String fileName, FullHttpRequest request) {
        File file = new File(BASE_PATH + fileName);
        if (file.isDirectory() || file.isHidden() || !file.exists()) {
            sendError(ctx, NOT_FOUND);
            return;
        }

        if (!file.isFile()) {
            sendError(ctx, FORBIDDEN);
            return;
        }

        RandomAccessFile raf;

        try {
            raf = new RandomAccessFile(file, "r");
        } catch (FileNotFoundException fnfe) {
            sendError(ctx, NOT_FOUND);
            return;
        }

        long fileLength = 0;
        try {
            fileLength = raf.length();
        } catch (IOException ex) {
            Logger.getLogger(HttpStaticFileServerHandler.class.getName()).log(Level.SEVERE, null, ex);
        }

        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        setContentLength(response, fileLength);
        setContentTypeHeader(response, file);
        
        //setDateAndCacheHeaders(response, file);
        if (isKeepAlive(request)) {
            response.headers().set(CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
        }

        // Write the initial line and the header.
        ctx.write(response);

        // Write the content.
        ChannelFuture sendFileFuture;
        DefaultFileRegion defaultRegion = new DefaultFileRegion(raf.getChannel(), 0, fileLength);
        sendFileFuture = ctx.write(defaultRegion);

        // Write the end marker
        ChannelFuture lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);

        // Decide whether to close the connection or not.
        if (!isKeepAlive(request)) {
            // Close the connection when the whole content is written out.
            lastContentFuture.addListener(ChannelFutureListener.CLOSE);
        }
    }

    /**
     * This will set the content types of files. If you want to support any files 
     * add the content type and corresponding file extension here.
     * @param response
     * @param file 
     */
    private static void setContentTypeHeader(HttpResponse response, File file) {
        MimetypesFileTypeMap mimeTypesMap = new MimetypesFileTypeMap();
        mimeTypesMap.addMimeTypes("image png tif jpg jpeg bmp");
        mimeTypesMap.addMimeTypes("text/plain txt");
        mimeTypesMap.addMimeTypes("application/pdf pdf");

        String mimeType = mimeTypesMap.getContentType(file);

        response.headers().set(CONTENT_TYPE, mimeType);
    }

    private void uploadFile(ChannelHandlerContext ctx, FullHttpRequest request) {
        
        // test comment
        try {
            decoder = new HttpPostRequestDecoder(factory, request);
            //System.out.println("decoder created");
        } catch (HttpPostRequestDecoder.ErrorDataDecoderException e1) {
            e1.printStackTrace();
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "Failed to decode file data");
            return;
        }

        readingChunks = HttpHeaders.isTransferEncodingChunked(request);

        if (decoder != null) {
            if (request instanceof HttpContent) {

                //System.out.println("request instance of HttpContent");

                // New chunk is received
                HttpContent chunk = (HttpContent) request;
                try {
                    decoder.offer(chunk);
                } catch (HttpPostRequestDecoder.ErrorDataDecoderException e1) {
                    e1.printStackTrace();
                    sendError(ctx, HttpResponseStatus.BAD_REQUEST, "Failed to decode file data");
                    return;
                }

                //System.out.println("before readHttpDataChunkByChunk");

                readHttpDataChunkByChunk(ctx);
                // example of reading only if at the end
                if (chunk instanceof LastHttpContent) {
                    readingChunks = false;
                    reset();
                }
            } else {
                sendError(ctx, HttpResponseStatus.BAD_REQUEST, "Not a http request");
            }
        } else {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "Failed to decode file data");
        }

    }
    
    private void sendOptionsRequestResponse(ChannelHandlerContext ctx){
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        response.headers().add("Access-Control-Allow-Origin", "*");
        response.headers().add("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        response.headers().add("Access-Control-Allow-Headers", 
                "X-Requested-With, Content-Type, Content-Length");
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private void sendUploadedFileName(String fileName, ChannelHandlerContext ctx) {
        JSONObject jsonObj = new JSONObject();

        String msg = "Unexpected error occurred";
        String contentType = "application/json; charset=UTF-8";
        HttpResponseStatus status = HttpResponseStatus.OK;

        try {
            jsonObj.put("file", fileName);
            msg = jsonObj.toString();

        } catch (JSONException ex) {
            Logger.getLogger(HttpStaticFileServerHandler.class.getName()).log(Level.SEVERE, null, ex);
            status = HttpResponseStatus.BAD_REQUEST;
            contentType = "text/plain; charset=UTF-8";
        }

        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer(msg, CharsetUtil.UTF_8));

        response.headers().set(CONTENT_TYPE, contentType);
        response.headers().add("Access-Control-Allow-Origin", "*");
        //setCrossDomainHeader(response);

        // Close the connection as soon as the error message is sent.
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private void reset() {
        //request = null;

        // destroy the decoder to release all resources
        decoder.destroy();
        decoder = null;
    }

    /**
     * Example of reading request by chunk and getting values from chunk to
     * chunk
     */
    private void readHttpDataChunkByChunk(ChannelHandlerContext ctx) {
        //decoder.isMultipart();
        if (decoder.isMultipart()) {
            try {
                while (decoder.hasNext()) {
                    //System.out.println("decoder has next");
                    InterfaceHttpData data = decoder.next();
                    if (data != null) {
                        writeHttpData(data, ctx);
                        data.release();
                    }
                }
            } catch (Exception e) {
                //e.printStackTrace();
            }
        } else {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "Not a multipart request");
        }

        //System.out.println("decoder has no next");
    }

    private void writeHttpData(InterfaceHttpData data, ChannelHandlerContext ctx) {

        if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.FileUpload) {
            FileUpload fileUpload = (FileUpload) data;

            if (fileUpload.isCompleted()) {
                String savedFile = saveFileToDisk(fileUpload);
                sendUploadedFileName(savedFile, ctx);
            } else {
                //responseContent.append("\tFile to be continued but should not!\r\n");
                sendError(ctx, HttpResponseStatus.BAD_REQUEST, "Unknown error occurred");
            }
        }
    }

    /**
     * Saves the uploaded file to disk.
     *
     * @param fileUpload FileUpload object that'll be saved
     * @return name of the saved file. null if error occurred
     */
    private String saveFileToDisk(FileUpload fileUpload) {
        String filePath = null;
        String fileName = System.currentTimeMillis() + "_" + fileUpload.getFilename();
        
        try {
            filePath = BASE_PATH + fileName;

            fileUpload.renameTo(new File(filePath)); // enable to move into another
        } catch (IOException ex) {
            fileName = null;
        }

        return fileName;
    }

    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String msg) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer(msg, CharsetUtil.UTF_8));
        response.headers().set(CONTENT_TYPE, "text/plain; charset=UTF-8");

        // Close the connection as soon as the error message is sent.
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
        sendError(ctx, status, "Failure: " + status.toString() + "\r\n");
    }

}
