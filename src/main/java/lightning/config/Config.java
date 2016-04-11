package lightning.config;

import java.util.List;
import java.util.concurrent.TimeUnit;

import lightning.ann.Optional;
import lightning.ann.Required;
import lightning.mail.MailerConfig;

import com.google.common.collect.ImmutableList;

public class Config {
  @Override
  public String toString() {
    return "Config [autoReloadPrefixes=" + autoReloadPrefixes + ", scanPrefixes=" + scanPrefixes
        + ", ssl=" + ssl + ", server=" + server + ", mail=" + mail + ", db=" + db
        + ", enableDebugMode=" + enableDebugMode + "]";
  }
  
  // Whether or not to enable debug mode.
  // Recommended for development, **DO NOT LEAVE ON IN PRODUCTION AS IT EXPOSES SYSTEM INTERNALS**!
  // Enables automatic hot swapping (reloading) of handler classes, exception stack traces in
  // the browser, template errors in browser, and disables HTTP caching of static files. 
  // Ensures templates and static files will always be reloaded from disk on each request. 
  // Essentially: quick and easy save and refresh development.
  // NOTE: Websockets are currently not automatically reloaded due to limitations in Jetty. You'll
  //       need to restart to server to see changes to websocket handler code.
  // Debug mode does not work when deployed to a JAR and assumes the current working directory is set
  // to the root of your project folder (where pom.xml is located).
  public @Optional boolean enableDebugMode = false;

  // A list of prefixes on which classes should be automatically reloaded on each incoming
  // request in DEBUG MODE ONLY.
  // You should ONLY put code that can be safely reloaded by the classloader within the packages 
  // indicated in these prefixes or you're going to see some strange behavior.
  // Dependencies that involve classes within these prefixes will not be properly injectable in debug mode,
  // so do net try to inject dependencies that are located within these prefixes.
  public @Optional List<String> autoReloadPrefixes = ImmutableList.of();
  
  // A list of prefixes in which to search for routes, websockets, exception handlers.
  public @Required List<String> scanPrefixes;
  
  public @Required SSLConfig ssl = new SSLConfig();
  public @Required ServerConfig server = new ServerConfig();
  public @Required MailConfig mail = new MailConfig();
  public @Required DBConfig db = new DBConfig();
  
  // TODO: Add options for FreeMarker config.
  // TODO: Add options for Whoops (debug screen) code search paths.
  // TODO: Add options for Sessions and Auth.
  
  public static final class SSLConfig {
    @Override
    public String toString() {
      return "SSLConfig [keyStoreFile=" + keyStoreFile + ", keyStorePassword=" + keyStorePassword
          + ", trustStoreFile=" + trustStoreFile + ", trustStorePassword=" + trustStorePassword
          + "]";
    }

    // For the Java Key Store (JKS):
    public @Optional String keyStoreFile;       // Required to enable SSL.
    public @Optional String keyStorePassword;   // Required to enable SSL.
    public @Optional String trustStoreFile;     // Optional.
    public @Optional String trustStorePassword; // Optional.
    public @Optional String keyManagerPassword; // Optional.
    
    // Whether to redirect HTTP requests to their HTTPS equivalents.
    // If false, will only install an HTTPs server (on ssl.port).
    // If true, will also install an HTTP server (on server.port) that redirects insecure requests.
    public @Optional boolean redirectInsecureRequests = true;
    
    // If SSL is enabled, the server will listen for HTTPS connections on this port.
    public @Optional int port = 443;
    
    public boolean isEnabled() {
      return keyStorePassword != null && keyStoreFile != null;
    }
  }
  
  public static final class ServerConfig {
    @Override
    public String toString() {
      return "ServerConfig [hmacKey=" + hmacKey + ", port=" + port + ", minThreads=" + minThreads
          + ", maxThreads=" + maxThreads + ", threadTimeoutMs=" + threadTimeoutMs
          + ", websocketTimeoutMs=" + websocketTimeoutMs + ", staticFilesPath=" + staticFilesPath
          + ", templateFilesPath=" + templateFilesPath + ", trustLoadBalancerHeaders="
          + trustLoadBalancerHeaders + "]";
    }
    
    // Enable HTTP/2 support.
    // Clients that do not support HTTP/2 will fall back to HTTP/1.1 (which, by definition, supports HTTP/1.0).
    // Remember that HTTP/2 is not supported over non-encrypted connections and should be used in combination with SSL.
    // IMPORTANT:
    //  HTTP/2 support requires ALPN to be placed in the JVM boot path for JDK <= 8. JDK9 should have built-in support for ALPN.
    //  To add ALPN for JDK <= 8:
    //    1) A specific version of ALPN is required based on your JVM. The ALPN version must match the version of your JVM.
    //       See https://www.eclipse.org/jetty/documentation/current/alpn-chapter.html#alpn-versions
    //    2) You can download the ALPN jar from Maven at:
    //       http://mvnrepository.com/artifact/org.mortbay.jetty.alpn/alpn-boot
    //    3) You must start your JVM with the following argument to enable ALPN:
    //       -Xbootclasspath/p:/path/to/alpn-boot-${alpn-version}.jar
    public @Optional boolean enableHttp2 = false;
    
    // Encryption key for verifying integrity of hashes generated by the server.
    // Should be something long, random, secret, and unique to your app.
    public @Required String hmacKey;
    
    // Port on which to listen for incoming HTTP connections.
    public @Optional int port = 80;
    
    // Minimum number of handler threads.
    public @Optional int minThreads = 40;
    
    // Maximmum number of handler threads.
    public @Optional int maxThreads = 250;
    
    // Jetty handler thread timeout.
    public @Optional int threadTimeoutMs = (int) TimeUnit.SECONDS.toMillis(60);
    
    // Jetty websocket timeout.
    public @Optional int websocketTimeoutMs = (int) TimeUnit.SECONDS.toMillis(60); // For websockets.
    
    // Maximum incoming request size (in bytes).
    public @Optional int maxPostBytes = 1024 * 1024 * 20; // 20 MB
    
    // Maxmimum number of query parameters on an incoming request (in #).
    public @Optional int maxQueryParams = 100; // In number of params.
    
    // Maximum amount of time allowed before closing an idle HTTP connection.
    public @Optional int connectionIdleTimeoutMs = (int) TimeUnit.MINUTES.toMillis(3);
    
    /**
     * Path from which to serve static files.
     * 
     * Static files will be served directly on their path within this folder (e.g. if style.css exists in here, it will
     * be accessible on /style.css).
     * 
     * Static files will be served optimally (memory caching, http caching) and with full support for the HTTP specification
     * (including ranges).
     * 
     * Static files will be served gzipped if a .gz version exists (e.g. styles.css.gz). 
     */
    public @Required String staticFilesPath; // Relative to src/main|resources/java in your eclipse project folder.
    
    // Path in which freemarker templates are located.
    public @Required String templateFilesPath; // Relative to src/main|resources/java in your eclipse project folder.
    
    // Host on which to listen.
    public @Optional String host = "0.0.0.0";
    
    // Whether or not to trust load balancer headers (X-Forwarded-For, X-Forwarded-Proto).
    // Will cause things like request.scheme() and request.method() to utilize the header information.
    // Enable only if your app is firewalled behind a load balancer.
    public @Optional boolean trustLoadBalancerHeaders = false;
    
    // Maximum size of a cached static file in bytes.
    public @Optional int maxCachedStaticFileSizeBytes = 1024 * 50; // 50KB
    
    // Maxmimum size of the static file cache in bytes.
    public @Optional int maxStaticFileCacheSizeBytes = 1024 * 1024 * 30; // 30MB
    
    // Maximum number of cached static files.
    public @Optional int maxCachedStaticFiles = 500;
    
    // Place in which multipart pieces that are flushed to disk will be saved.
    public @Optional String multipartSaveLocation = System.getProperty("java.io.tmpdir");
    
    // Size at which a multipart piece will be flushed to disk.
    public @Optional int multipartFlushSizeBytes = 1024 * 1024 * 10; // 10 MB
    
    // Maximum size of a multipart piece.
    public @Optional int multipartPieceLimitBytes = 1024 * 1024 * 100; // 100 MB
    
    // Maximum size of a multipart request.
    public @Optional int multipartRequestLimitBytes = 1024 * 1024 * 100; // 100 MB
    
    // Whether or not to accept multipart requests.
    // If false, all multipart requests will be dropped.
    public @Optional boolean multipartEnabled = true;
    
    // NOTE: Want custom error pages (e.g. for 404 and 500 errors)? Install an @ExceptionHandler
    // for the exceptions in lightning.http.
  }
  
  public static final class MailConfig implements MailerConfig {
    @Override
    public String toString() {
      return "MailConfig [useSSL=" + useSsl + ", address=" + address + ", username=" + username
          + ", password=" + password + ", host=" + host + ", port=" + port + "]";
    }

    // Whether or not to use SMTP over SSL.
    public @Optional boolean useSsl = true;
    
    // Email address
    public @Optional String address;
    
    // SMTP login
    public @Optional String username;
    
    // SMTP password
    public @Optional String password;
    
    // SMTP host
    public @Optional String host;
    
    // SMTP port
    public @Optional int port = 465;
    
    public boolean isEnabled() {
      return host != null && username != null;
    }
    
    @Override
    public String getAddress() {
      return address;
    }
    
    @Override
    public int getPort() {
      return port;
    }
    
    @Override
    public boolean useSSL() {
      return useSsl;
    }
    
    @Override
    public String getHost() {
      return host;
    }
    
    @Override
    public String getUsername() {
      return username;
    }
    
    @Override
    public String getPassword() {
      return password;
    }
  }
  
  public static final class DBConfig {
    @Override
    public String toString() {
      return "DBConfig [host=" + host + ", port=" + port + ", username=" + username + ", password="
          + password + ", name=" + name + "]";
    }
    
    // MySQL database host
    public @Optional String host = "localhost";
    
    // MySQL database port
    public @Optional int port = 3306;
    
    // MySQL database username
    public @Optional String username = "httpd";
    
    // MySQL database password
    public @Optional String password = "httpd";
    
    // MySQL database name
    public @Optional String name;
    
    // Connection pooling options:
    public @Optional int minPoolSize = 5;
    public @Optional int maxPoolSize = 100;
    public @Optional int acquireIncrement = 5;
    public @Optional int acquireRetryAttempts = 3;
    public @Optional int acquireRetryDelayMs = 1000;
    public @Optional boolean autoCommitOnClose = true;
    public @Optional int maxConnectionAgeS = 50000;
    public @Optional int maxIdleTimeS = 50000;
    public @Optional int maxIdleTimeExcessConnectionsS = 50000;
    public @Optional int unreturnedConnectionTimeoutS = 50000;
    public @Optional int idleConnectionTestPeriodS = 600;
    public @Optional int maxStatementsCached = 500;
    public @Optional boolean useSsl = false; // Must have cert for server in your trust store.
  }
}
