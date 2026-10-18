package local.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.net.URI;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
class LocalSecurity extends OncePerRequestFilter {
    static final String CSRF="X-CSRF-Token";
    private static final SecureRandom RANDOM=new SecureRandom();
    private final ObjectMapper json;
    private final Map<String,Window> windows=new ConcurrentHashMap<>();
    LocalSecurity(ObjectMapper json) { this.json=json; }
    static String randomToken() { byte[] b=new byte[32]; RANDOM.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    static String csrf(HttpSession session) {
        synchronized(session) {
            if(session.getAttribute("csrf")==null) session.setAttribute("csrf",randomToken());
            return (String)session.getAttribute("csrf");
        }
    }
    static String username(HttpServletRequest request) {
        HttpSession session=request.getSession(false);
        if(session==null || session.getAttribute("username")==null) throw new ApiError(401,"unauthenticated","请先注册或登录");
        return (String)session.getAttribute("username");
    }
    static void login(HttpServletRequest request,String username) {
        var previous=request.getSession(false); if(previous!=null) previous.invalidate();
        HttpSession session=request.getSession(true); session.setAttribute("username",username); csrf(session);
    }
    static boolean loopback(String host) { return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host) || "::1".equals(host); }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        response.setHeader("X-Content-Type-Options","nosniff"); response.setHeader("X-Frame-Options","DENY");
        response.setHeader("Referrer-Policy","no-referrer"); response.setHeader("Cache-Control","no-store");
        response.setHeader("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'");
        try {
            if(!loopback(request.getServerName())) throw new ApiError(403,"origin_denied","仅允许本机访问");
            String origin=request.getHeader("Origin");
            if(origin!=null) {
                URI uri;
                try { uri=URI.create(origin); } catch(IllegalArgumentException e) { throw new ApiError(403,"origin_denied","来源不受信任"); }
                int port=uri.getPort()==-1?("https".equals(uri.getScheme())?443:80):uri.getPort();
                if(!"http".equals(uri.getScheme()) || !loopback(uri.getHost()) || uri.getUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null || (uri.getRawPath()!=null&&!uri.getRawPath().isEmpty()) || !(port==request.getServerPort() || Set.of(5173,5174,5198).contains(port))) throw new ApiError(403,"origin_denied","来源不受信任");
            }
            if(request.getRequestURI().startsWith("/api/")) {
                if(request.getContentLengthLong()>32768) throw ApiError.invalid("请求正文不得超过32KB");
                String fetchSite=request.getHeader("Sec-Fetch-Site");
                if("cross-site".equals(fetchSite)) throw new ApiError(403,"origin_denied","不允许跨站请求");
                boolean write=!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod());
                if(write) {
                    HttpSession session=request.getSession(false); String provided=request.getHeader(CSRF);
                    if(session==null || provided==null || !MessageDigest.isEqual(csrf(session).getBytes(java.nio.charset.StandardCharsets.UTF_8),provided.getBytes(java.nio.charset.StandardCharsets.UTF_8))) throw new ApiError(403,"csrf_invalid","页面状态已变化，请刷新后重试");
                    limit("writes:"+session.getId(),240);
                }
                chain.doFilter(new BoundedRequest(request),response);
            } else chain.doFilter(request,response);
        } catch(ApiError e) { error(response,e); }
    }
    void authLimit(String username,String remote) { limit("auth:"+remote+":"+username,12); }
    private void limit(String key,int maximum) {
        long minute=Instant.now().getEpochSecond()/60;
        if(windows.size()>10000) windows.entrySet().removeIf(entry->entry.getValue().minute<minute);
        Window w=windows.compute(key,(k,current)->current==null||current.minute!=minute?new Window(minute):current);
        synchronized(w) { if(++w.count>maximum) throw new ApiError(429,"rate_limited","请求过于频繁，请稍后重试"); }
    }
    private void error(HttpServletResponse response,ApiError e) throws IOException {
        response.setStatus(e.status); response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getWriter(),Map.of("code",e.code,"message",e.getMessage()));
    }
    private static final class Window { final long minute; int count; Window(long minute){this.minute=minute;} }
    private static final class BoundedRequest extends HttpServletRequestWrapper {
        BoundedRequest(HttpServletRequest request) { super(request); }
        @Override public ServletInputStream getInputStream() throws IOException {
            ServletInputStream original=super.getInputStream();
            return new ServletInputStream() {
                int count;
                @Override public int read() throws IOException { int value=original.read(); if(value!=-1 && ++count>32768) throw new IOException("Request body too large"); return value; }
                @Override public boolean isFinished(){return original.isFinished();}
                @Override public boolean isReady(){return original.isReady();}
                @Override public void setReadListener(ReadListener listener){original.setReadListener(listener);}
            };
        }
    }
}
