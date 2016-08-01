package lightning.config;

import java.util.List;
import java.util.concurrent.TimeUnit;

import lightning.ann.Optional;
import lightning.ann.Required;
import lightning.mail.MailerConfig;

import com.google.common.collect.ImmutableList;

/**
 * Provides configuration options for most areas of Lightning.
 * See the official web documentation for more information.
 */
public class Config {
  /**
   * Specifies whether or not debug (development) mode should be enabled.
   * Recommended during development.
   * 
   * Assumes the current working directory is set to the root of your project folder (where pom.xml is) and
   * that you are following the Maven directory structure conventions.
   * 
   * WARNING:
   *   DO NOT ENABLE DEBUG MODE IN PRODUCTION! IT MAY EXPOSE SYSTEM INTERNALS!
   *   DEBUG MODE WILL NOT WORK WHEN DEPLOYED TO A JAR!
   * 
   * NOTE:
   *   Debug Mode enables automatic code reloading without restarting the server, adds in-browser exception
   *   stack traces and template errors, disables caching of static files and template files, disables HTTP
   *   caching of static files, etc.
   *   
   * LIMITATIONS:
   *   Web sockets are not automatically reloadable (due to limitations in Jetty which underlies our built-in
   *   web server) but may be in future releases. For now, you will need to restart the server to see changes
   *   reflected to web socket code.
   */
  public @Optional boolean enableDebugMode = false;

  /**
   * Specifies a list of Java package prefixes in which code can be safely reloaded on each incoming request.
   * Code reloading will ONLY OCCUR WHEN DEBUG MODE IS ENABLED.
   * 
   * WARNING:
   *   Keep in mind that NOT ALL CODE CAN BE SAFELY RELOADED. If you incorrectly configure this option, you may
   *   experience some strange or undefined behavior when running the server.
   *   Keep in mind that you MAY NOT dependency inject any classes which are located within these prefixes.
   *   Keep in mind that you MAY NOT dependency inject classes with static state.
   */
  public @Optional List<String> autoReloadPrefixes = ImmutableList.of();
  
  /**
   * Specifies a list of Java package prefixes in which to search for routes, web sockets, exception handlers.
   * Example: ImmutableList.of("path.to.my.app")
   */
  public @Required List<String> scanPrefixes;
  
  // TODO: Add options for Whoops (debug screen) code search paths.
  // TODO: Add options for Sessions and Auth.
  
  /**
   * Provides options for enabling SSL with the built-in server.
   */
  public @Required SSLConfig ssl = new SSLConfig();
  public static final class SSLConfig {
    /************************************
     * Java Key Store Options
     ************************************
     *
     * SSL will be enabled if you provide a keystore file and keystore password.
     * The specified keystore (.jks) should contain the server's SSL certificate.
     * You may wish to read more Java Key Store (JKS) format.
     */
    
    public @Optional String keyStoreFile;       // Required to enable SSL.
    public @Optional String keyStorePassword;   // Required to enable SSL.
    public @Optional String trustStoreFile;     // Optional.
    public @Optional String trustStorePassword; // Optional.
    public @Optional String keyManagerPassword; // Optional.
    
    /************************************
     * Server Options
     ***********************************/
    
    /**
     * Whether to redirect HTTP requests to their HTTPS equivalents.
     * If false, will only install an HTTPs server (on ssl.port).
     * If true, will also install an HTTP server (on server.port) that redirects insecure requests.
     */
    public @Optional boolean redirectInsecureRequests = true;
    
    /**
     * Specifies the port on which to listen for HTTPS connections.
     */
    public @Optional int port = 443;
    
    public boolean isEnabled() {
      return keyStorePassword != null && keyStoreFile != null;
    }
  }
  
  /**
   * Provides options for configuring the built-in HTTP server.
   * 
   * NOTE:
   *   For custom HTTP error pages, see the documentation for lightning.ann.ExceptionHandler.
   */
  public @Required ServerConfig server = new ServerConfig();
  public static final class ServerConfig {    

    //    3) You must start your JVM with the following argument to enable ALPN:
    //       -Xbootclasspath/p:/path/to/alpn-boot-${alpn-version}.jar
    
    /**
     * Enables server-side HTTP/2 support.
     * Clients that do not support HTTP/2 will fall back to HTTP/1.1 or HTTP/1.0.
     * 
     * NOTE:
     *   No clients currently support HTTP/2 over unencrypted connections. HTTP/2 must be used in combination with SSL.
     * 
     * IMPORTANT:
     *   Due to changes to SSL protocol negotiation in HTTP/2 (via ALPN), you must replace your JDK's implementation of
     *   SSL with an implementation that adds support for ALPN (Application-Layer Protocol Negotiation).
     *   
     *   This is MANDATORY for enabling HTTP/2 with JRE <= 8. JRE 9+ should have built-in support for ALPN.
     *   
     *   You may add ALPN support by placing the ALPN library in your JVM boot path as follows:
     *   1) Determine the SPECIFIC VERSION of ALPN required for YOUR JVM VERSION.
     *      The ALPN version MUST MATCH the version of your JVM.
     *      SEE: https://www.eclipse.org/jetty/documentation/current/alpn-chapter.html#alpn-versions
     *   2) Download the SPECIFIC VERSION of ALPN for YOUR JVM version as a JAR.
     *      SEE: http://mvnrepository.com/artifact/org.mortbay.jetty.alpn/alpn-boot
     *   3) You must launch your JVM with the following command line arguments to add ALPN to your JVM boot path:
     *      -Xbootclasspath/p:/path/to/alpn-boot-${alpn-version}.jar
     */
    public @Optional boolean enableHttp2 = false;
    
    /**
     * Sets the private encryption key used for verifying the integrity of hashes
     * generated by the server (for example, cookie signatures).
     * 
     * NOTE:
     *   Must be something long, random, secret, and unique to your app.
     *   In clustered environments, all app servers must use the same key.
     */
    public @Required String hmacKey;
    
    /**
     * Sets the port on which the server will listen for incoming HTTP connections.
     */
    public @Optional int port = 80;
    
    /**
     * Sets the minimum size of the server request processing thread pool.
     */
    public @Optional int minThreads = 40;
    
    /**
     * Sets the maximum size of the server request processing thread pool.
     */
    public @Optional int maxThreads = 250;
    
    /**
     * Sets the maximum amount of time that server thread may be idle before
     * being removed from the server request processing thread pool.
     */
    public @Optional int threadTimeoutMs = (int) TimeUnit.SECONDS.toMillis(60);
    
    /**
     * Sets the maximum amount of time that a websocket connection may be idle
     * before the server forcibly closes the connection.
     */
    public @Optional int websocketTimeoutMs = (int) TimeUnit.SECONDS.toMillis(60);
    
    /**
     * Sets the maximum binary message siez that the server will accept over websocket
     * connections.
     */
    public @Optional int websocketMaxBinaryMessageSizeBytes = 65535;
    
    /**
     * Sets the maximum text message size that the server will accept over websocket
     * connections.
     */
    public @Optional int websocketMaxTextMessageSizeBytes = 65535;
    
    /**
     * Whether or not to enable message compression (via permessage-deflate) on websocket
     * connections.
     */
    public @Optional boolean websocketEnableCompression = true;
    
    /**
     * Sets the default timeout of async write operations on websocket connections.
     */
    public @Optional long websocketAsyncWriteTimeoutMs = TimeUnit.SECONDS.toMillis(60);
    
    /**
     * Sets the maximum size of an incoming non-multipart request.
     * Requests in excess of this limit will be dropped.
     */
    public @Optional int maxPostBytes = 1024 * 1024 * 20; // 20 MB
    
    /**
     * Sets the maximum number of query parameters that a request may contain.
     * Requests in excess of this limit will be dropped.
     */
    public @Optional int maxQueryParams = 100; // In number of params.
    
    /**
     * Sets the maximum amount of time that an HTTP connection may be idle before the
     * server forcibly closes it.
     */
    public @Optional int connectionIdleTimeoutMs = (int) TimeUnit.MINUTES.toMillis(3);
    
    /**
     * Specifies the path containing static files that should be served.
     * This path is relative to ${project}/src/main/resources.
     * 
     * Static files will be served on their path relative to their location within the specified folder.
     * For example, "style.css" in the specified folder will be served on "/style.css".
     * 
     * Static files are served optimally (memory caching, HTTP caching) and support the entire HTTP spec,
     * including range requests.
     * 
     * Static files will be served gzipped if the client supports it and a pre-gzipped version of the file
     * exists (e.g. styles.css.gz).
     */
    public @Optional String staticFilesPath; 
    
    /**
     * Specifies the path in which template files are located.
     * This path is relative to ${project}/src/main/resources.
     */
    public @Optional String templateFilesPath; 
    
    /**
     * Sets the host on which the server should listen.
     * NOTE: "0.0.0.0" matches any host.
     */
    public @Optional String host = "0.0.0.0";
    
    /**
     * Whether or not to trust load balancer headers (X-Forwarded-For, X-Forwarded-Proto).
     * 
     * NOTE:
     *   Enabling this option will cause Lightning APIs (e.g. request.scheme() and request.method()) 
     *   to utilize this header information. This option should be enabled only if your app servers 
     *   are safely firewalled behind a load balancer (such as Amazon ELB).
     */
    public @Optional boolean trustLoadBalancerHeaders = false;
    
    /**
     * Sets the maximum size at which an individual static file may be RAM-cached.
     */
    public @Optional int maxCachedStaticFileSizeBytes = 1024 * 50; // 50KB
    
    /**
     * Sets the maximum size of the RAM-cache for static files.
     */
    public @Optional int maxStaticFileCacheSizeBytes = 1024 * 1024 * 30; // 30MB
    
    /**
     * Sets the maximum number of static files that may be RAM-cached.
     */
    public @Optional int maxCachedStaticFiles = 500;
    
    /**
     * Whether or not to enable server support for HTTP multipart requests.
     * If not enabled, multipart requests will be dropped.
     */
    public @Optional boolean multipartEnabled = true;
    
    /**
     * A temporary directory in which multipart pieces that exceed the flush size may
     * be written to disk in order to avoid consuming significant RAM.
     */
    public @Optional String multipartSaveLocation = System.getProperty("java.io.tmpdir");
    
    /**
     * Size at which a multipart piece will be flushed to disk.
     * Pieces less than this size will be stored in RAM.
     */
    public @Optional int multipartFlushSizeBytes = 1024 * 1024 * 1; // 1 MB
    
    /**
     * Maximum allowed size of a individual multipart piece.
     * Requests containing pieces larger than this size will be dropped.
     * NOTE: This will limit the maximum file upload size.
     */
    public @Optional int multipartPieceLimitBytes = 1024 * 1024 * 100; // 100 MB
    
    /**
     * Maximum allowed size of an entire multipart request.
     * Requests in excess of this size will be dropped.
     * NOTE: This will limit the maximum file upload size.
     */
    public @Optional int multipartRequestLimitBytes = 1024 * 1024 * 100; // 100 MB

    /**
     * Sets the maximum number of queued requests.
     * Requests past this limit will be dropped.
     */
    public @Optional int maxQueuedRequests = 10000;

    /**
     * Sets the maximum number of queued unaccepted connections.
     * Connection attempts past this limit will be dropped.
     */
    public @Optional int maxAcceptQueueSize = 0;
    
    /**
     * Whether or not to enable server support for HTTP persistent connections.
     */
    public @Optional boolean enablePersistentConnections = true;
    
    // TODO: Add options for configuring HTTP buffer sizes.
  }
  
  /**
   * Provides options for configuring the sending of emails over SMTP.
   */
  public @Required MailConfig mail = new MailConfig();
  public static final class MailConfig implements MailerConfig {
    /**
     * Forces messages to be logged (via the SLF4J facade) instead of attempting
     * to deliver them over the network. Useful for development.
     */
    public @Optional boolean useLogDriver = false;

    /**
     * Whether or not to use SMTP+SSL.
     */
    public @Optional boolean useSsl = true;
    
    /**
     * The email address from which the server will deliver messages.
     */
    public @Optional String address;
    
    /**
     * The username required to authenticate with the SMTP server.
     */
    public @Optional String username;
    
    /**
     * The password required to authenticate with the SMTP server.
     * Set to NULL if no password is required.
     */
    public @Optional String password;
    
    /**
     * The host of the SMTP server.
     */
    public @Optional String host;
    
    /**
     * The port of the SMTP server.
     */
    public @Optional int port = 465;
    
    public boolean isEnabled() {
      return useLogDriver ||  (host != null && username != null);
    }
    
    @Override
    public boolean useLogDriver() {
      return useLogDriver;
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
  
  /**
   * Provides options for configuring a connection pool to an SQL database.
   */
  public @Required DBConfig db = new DBConfig();
  public static final class DBConfig {
    /*********************************************
     * Database Information
     *********************************************/
    
    public @Optional String host = "localhost";
    public @Optional int port = 3306;
    public @Optional String username = "httpd";
    
    /**
     * NOTE: Leave NULL if no password is required (not recommended).
     */
    public @Optional String password = "httpd";
    
    /**
     * The name of the default database to use.
     */
    public @Optional String name;
    
    /**
     * Whether to connect to the database using SSL.
     * 
     * NOTE: 
     *   The server certificate must be trusted by your JVM by default or explicitly placed
     *   in your JVM trust store.
     */
    public @Optional boolean useSsl = false;
    
    /*********************************************
     * Connection Pool Options
     *********************************************/
    
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
  }
}
