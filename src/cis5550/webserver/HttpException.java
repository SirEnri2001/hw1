package cis5550.webserver;

public class HttpException extends Exception{
    public int errCode;
    public String description;
    public HttpException(int errCode, String description) {
        this.errCode = errCode;
        this.description = description;
    }
}
