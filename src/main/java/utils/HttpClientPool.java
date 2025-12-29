package utils;

import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @author by laugh on 2016/3/29.
 */
public class HttpClientPool {

    private static volatile HttpClientPool clientInstance;
    private HttpClient httpClient;

    public static HttpClientPool getHttpClient() {
        HttpClientPool tmp = clientInstance;
        if (tmp == null) {
            synchronized (HttpClientPool.class) {
                tmp = clientInstance;
                if (tmp == null) {
                    tmp = new HttpClientPool();
                    clientInstance = tmp;
                }
            }
        }
        return tmp;
    }

    private HttpClientPool() {
        buildHttpClient(null);
    }

    public void buildHttpClient(String proxyStr){
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager(100, TimeUnit.SECONDS);
        connectionManager.setMaxTotal(200);// 连接池
        connectionManager.setDefaultMaxPerRoute(100);// 每条通道的并发连接数
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(5000)  // 连接请求超时5秒
                .setConnectTimeout(5000)            // 连接超时5秒
                .setSocketTimeout(10000)             // Socket超时10秒
                .build();
        HttpClientBuilder httpClientBuilder = HttpClients.custom().setConnectionManager(connectionManager);
        if (proxyStr!=null && !proxyStr.isEmpty()){
            String[] s = proxyStr.split(":");
            if (s.length == 2){
                String host = s[0];
                int port = Integer.parseInt(s[1]);
                httpClientBuilder.setProxy(new HttpHost(host,port));
            }
            LogUtil.info("Leeks setup proxy success->"+proxyStr);
        }
        httpClient =httpClientBuilder.setDefaultRequestConfig(requestConfig).build();
    }

    public String get(String url) throws Exception {
        HttpGet httpGet = new HttpGet(url);
        return getResponseContent(url,httpGet);
    }

    public String get(String url, Map<String, String> headers) throws Exception {
        HttpGet httpGet = new HttpGet(url);
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                httpGet.setHeader(entry.getKey(), entry.getValue());
            }
        }
        return getResponseContent(url, httpGet);
    }

    public String post(String url) throws Exception {
        HttpPost httpPost = new HttpPost(url);
        return getResponseContent(url, httpPost);
    }

    public String post(String url, String body, Map<String, String> headers) throws Exception {
        HttpPost httpPost = new HttpPost(url);
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                httpPost.setHeader(entry.getKey(), entry.getValue());
            }
        }
        if (body != null && !body.isEmpty()) {
            httpPost.setEntity(new StringEntity(body, StandardCharsets.UTF_8));
        }
        return getResponseContent(url, httpPost);
    }

    private String getResponseContent(String url, HttpRequestBase request) throws Exception {
        HttpResponse response = null;
        try {
            response = httpClient.execute(request);
            int statusCode = response.getStatusLine().getStatusCode();
            String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            
            // 检查HTTP状态码
            if (statusCode >= 200 && statusCode < 300) {
                return responseBody;
            } else {
                // 非2xx状态码，返回详细错误信息
                String errorMsg = String.format("HTTP %d %s - URL: %s, Response: %s", 
                    statusCode, 
                    response.getStatusLine().getReasonPhrase(),
                    URLDecoder.decode(url, "UTF-8"),
                    responseBody.length() > 500 ? responseBody.substring(0, 500) + "..." : responseBody);
                throw new Exception(errorMsg);
            }
        } catch (Exception e) {
            // 如果已经有详细错误信息，直接抛出
            if (e.getMessage() != null && e.getMessage().startsWith("HTTP")) {
                throw e;
            }
            // 否则包装原始异常
            throw new Exception("got an error from HTTP for url : " + URLDecoder.decode(url, "UTF-8"), e);
        } finally {
            if(response != null){
                EntityUtils.consumeQuietly(response.getEntity());
            }
            request.releaseConnection();
        }
    }
}