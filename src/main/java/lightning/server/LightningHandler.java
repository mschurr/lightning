package lightning.server;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import lightning.ann.Before;
import lightning.ann.Befores;
import lightning.ann.ExceptionHandler;
import lightning.ann.Filter;
import lightning.ann.Filters;
import lightning.ann.Json;
import lightning.ann.Multipart;
import lightning.ann.RequireAuth;
import lightning.ann.RequireXsrfToken;
import lightning.ann.Route;
import lightning.ann.Routes;
import lightning.ann.Template;
import lightning.ann.WebSocket;
import lightning.cache.Cache;
import lightning.cache.CacheDriver;
import lightning.cache.driver.ExceptingCacheDriver;
import lightning.config.Config;
import lightning.db.MySQLDatabaseProvider;
import lightning.debugmap.DebugMapController;
import lightning.debugscreen.DebugScreen;
import lightning.debugscreen.LocalSourceLocator;
import lightning.debugscreen.SourceLocator;
import lightning.enums.HTTPMethod;
import lightning.enums.HTTPStatus;
import lightning.exceptions.LightningException;
import lightning.fn.ExceptionViewProducer;
import lightning.fn.RouteFilter;
import lightning.healthscreen.HealthScreenController;
import lightning.http.AccessViolationException;
import lightning.http.BadRequestException;
import lightning.http.HaltException;
import lightning.http.InternalRequest;
import lightning.http.InternalResponse;
import lightning.http.InternalServerErrorException;
import lightning.http.MethodNotAllowedException;
import lightning.http.NotAuthorizedException;
import lightning.http.NotFoundException;
import lightning.http.NotImplementedException;
import lightning.inject.Injector;
import lightning.inject.InjectorModule;
import lightning.io.BufferingHttpServletResponse;
import lightning.io.FileServer;
import lightning.json.GsonJsonService;
import lightning.json.JsonService;
import lightning.mail.Mailer;
import lightning.mvc.DefaultExceptionViewProducer;
import lightning.mvc.HandlerContext;
import lightning.mvc.ModelAndView;
import lightning.routing.ExceptionMapper;
import lightning.routing.FilterMapper;
import lightning.routing.FilterMapper.FilterMatch;
import lightning.routing.RouteMapper;
import lightning.routing.RouteMapper.Match;
import lightning.scanner.ScanResult;
import lightning.scanner.Scanner;
import lightning.templates.FreeMarkerTemplateEngine;
import lightning.templates.TemplateEngine;
import lightning.util.MimeMatcher;
import lightning.websockets.LightningWebSocketCreator;
import lightning.websockets.WebSocketHandler;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.util.MultiPartInputStreamParser;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.server.WebSocketServerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.augustl.pathtravelagent.PathFormatException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

public final class LightningHandler extends AbstractHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(LightningHandler.class);
  @SuppressWarnings("unchecked")
  private static final ImmutableSet<Class<? extends Throwable>> INTERNAL_EXCEPTIONS = ImmutableSet.of(
      NotFoundException.class, 
      BadRequestException.class, 
      NotAuthorizedException.class,
      AccessViolationException.class, 
      InternalServerErrorException.class, 
      MethodNotAllowedException.class,
      NotImplementedException.class);
  
  private final Scanner scanner;
  private final Mailer mailer;
  private final Config config;
  private final MySQLDatabaseProvider dbProvider;
  private final TemplateEngine userTemplateEngine;
  private final TemplateEngine internalTemplateEngine;
  private final ExceptionMapper<Method> exceptionHandlers;
  private final InjectorModule userInjectorModule;
  private final InjectorModule globalInjectorModule;
  private final FileServer fileServer;
  private final RouteMapper<Object> routes;
  private final FilterMapper<Method> filters;
  private final JsonService jsonService;
  private final Cache cache;
  private final DebugScreen debugScreen;
  private final ExceptionViewProducer exceptionViews;
  private final MimeMatcher outputBufferMatcher;
  
  private WebSocketServerFactory webSocketFactory;
  private ScanResult scanResult;
  private Map<String, LightningWebSocketCreator> singletonWebSockets;
  
  public LightningHandler(Config config, 
                          MySQLDatabaseProvider dbProvider, 
                          InjectorModule userInjectorModule) throws Exception {
    this.config = config;
    this.dbProvider = dbProvider;
    this.userInjectorModule = userInjectorModule;
    this.scanResult = null;
    this.filters = new FilterMapper<>();
    this.exceptionHandlers = new ExceptionMapper<>();
    this.scanner = new Scanner(config.autoReloadPrefixes, config.scanPrefixes, config.enableDebugMode);
    this.routes = new RouteMapper<>();
    this.internalTemplateEngine = new FreeMarkerTemplateEngine(getClass(), "/lightning");
    this.exceptionViews = new DefaultExceptionViewProducer();
    
    // Set up user template engine.
    {
      TemplateEngine engine = userInjectorModule.getBindingForClass(TemplateEngine.class);
      this.userTemplateEngine = (engine != null) ? engine : new FreeMarkerTemplateEngine(config);
    }
    
    // Set up output buffering.
    {
      if (config.server.enableOutputBuffering) {
        this.outputBufferMatcher = new MimeMatcher(config.server.outputBufferingTypes);
      } else {
        this.outputBufferMatcher = null;
      }
    }
    
    // Set up user json service.
    {
      JsonService service = userInjectorModule.getBindingForClass(JsonService.class);
      this.jsonService = (service != null) ? service : new GsonJsonService();
    }
    
    // Set up cache driver.
    {
      CacheDriver driver = userInjectorModule.getBindingForClass(CacheDriver.class);
      this.cache = new Cache((driver != null) ? driver : new ExceptingCacheDriver());
    }
    
    // Set up debug screen.
    {
      SourceLocator[] locators = new SourceLocator[config.codeSearchPaths.size()];
      int i = 0;
      for (String path : config.codeSearchPaths) {
        locators[i++] = new LocalSourceLocator(path);
      }
      this.debugScreen = new DebugScreen(config, locators);
    }
    
    // Set up mail.
    if (config.mail.isEnabled()) {
      this.mailer = new Mailer(config.mail);
    } else {
      this.mailer = null;
    }
    
    // Set up static files.
    {
      if (config.server.staticFilesPath != null) {        
        ResourceFactory factory = (config.enableDebugMode) 
            ? new ResourceCollection(getStaticFileResourcePaths())
            : Resource.newClassPathResource(config.server.staticFilesPath);
        this.fileServer = new FileServer(factory);
        this.fileServer.usePublicCaching();
        this.fileServer.setMaxCachedFiles(config.server.maxCachedStaticFiles);
        this.fileServer.setMaxCachedFileSize(config.server.maxCachedStaticFileSizeBytes);
        this.fileServer.setMaxCacheSize(config.server.maxStaticFileCacheSizeBytes);
        
        if (config.enableDebugMode) {
          this.fileServer.disableCaching();
        }
      } else {
        this.fileServer = null;
      }
    }
    
    // Set up web sockets.
    {
      this.singletonWebSockets = (config.enableDebugMode) ? new HashMap<>() : null;
    }
    
    // Set up the global injection module.
    {
      this.globalInjectorModule = new InjectorModule();
      this.globalInjectorModule.bindClassToInstance(LightningHandler.class, this);
      this.globalInjectorModule.bindClassToInstance(Config.class, this.config);
      this.globalInjectorModule.bindClassToInstance(MySQLDatabaseProvider.class, this.dbProvider);
      this.globalInjectorModule.bindClassToInstance(Mailer.class, this.mailer);
    }
    
    rescan();
  }
  
  private Resource[] getStaticFileResourcePaths() {
    assert (config.server.staticFilesPath != null && config.enableDebugMode);
    ArrayList<Resource> resources = new ArrayList<>();
    List<File> possible = ImmutableList.of(
      new File("./src/main/resources", config.server.staticFilesPath),
      new File("./src/main/java", config.server.staticFilesPath),
      new File(config.server.staticFilesPath)
    );
    
    for (File f : possible) {
      if (f.exists() && f.isDirectory() && f.canRead()) {
        resources.add(Resource.newResource(f));
      }
    }
    
    return resources.toArray(new Resource[resources.size()]);
  }
  
  public synchronized ScanResult getLastScanResult() {
    // For use in debug map page.
    return scanResult;
  }
  
  public synchronized Match<Object> getRouteMatch(String path, HTTPMethod method) throws PathFormatException {
    // For use in debug map page.
    return routes.lookup(path, method);
  }
  
  public synchronized FilterMatch<Method> getFilterMatch(String path, HTTPMethod method) throws PathFormatException {
    // For use in debug map page.
    return filters.lookup(path, method);
  }
  
  @Override
  public void destroy() {
   // TODO: Probably a few other things that need to be cleaned up.
   fileServer.destroy();
   super.destroy();
  }
  
  @Override
  protected void doStart() throws Exception {
    // Set up web socket factory.
    {
      WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
      policy.setIdleTimeout(config.server.websocketTimeoutMs);
      policy.setMaxBinaryMessageSize(config.server.websocketMaxBinaryMessageSizeBytes);
      policy.setMaxTextMessageSize(config.server.websocketMaxTextMessageSizeBytes);
      policy.setAsyncWriteTimeout(config.server.websocketAsyncWriteTimeoutMs);
      policy.setInputBufferSize(config.server.inputBufferSizeBytes);
      
      Constructor<WebSocketServerFactory> c = 
          WebSocketServerFactory.class.getDeclaredConstructor(
              WebSocketPolicy.class, Executor.class, ByteBufferPool.class);
      c.setAccessible(true);
      webSocketFactory = c.newInstance(policy, getServer().getThreadPool(), new MappedByteBufferPool());

      if(!config.server.websocketEnableCompression) {
        this.webSocketFactory.getExtensionFactory().unregister("permessage-deflate");
        this.webSocketFactory.getExtensionFactory().unregister("deflate-frame");
        this.webSocketFactory.getExtensionFactory().unregister("x-webkit-deflate-frame");
      }
    }
    
    addBean(webSocketFactory);
    super.doStart();
  }
  
  public void sendErrorPage(HttpServletRequest request,
                             HttpServletResponse response,
                             Throwable error,
                             Match<Object> route) throws ServletException, IOException {
    if (response.isCommitted()) {
      LOGGER.warn("Failed to render an error page (response already committed).");
      return; // We can't render an error page if the response is committed.
    }
    
    Method exceptionHandler = exceptionHandlers.getHandler(error);
    
    if (exceptionHandler == null) {
      sendBuiltInErrorPage(request, response, error, route);
      return;
    }

    response.reset();
    response.addHeader("Content-Type", "text/html; charset=UTF-8");    
    
    try {
      HandlerContext context = context(request, response);
      context.bindings().bindToClass(error);
      exceptionHandler.invoke(null, context.injector().getInjectedArguments(exceptionHandler));
    } catch (Throwable exceptionHandlerError) {
      exceptionHandlerError.addSuppressed(error);
      LOGGER.warn("An exception handler returned an exception:", exceptionHandlerError);
      
      // If the user exception handler threw an exception (not unlikely), we can try to render
      // the built-in page instead.
      sendBuiltInErrorPage(request, response, exceptionHandlerError, route);
    }
  }
  
  public void sendBuiltInErrorPage(HttpServletRequest request,
                                    HttpServletResponse response,
                                    Throwable error,
                                    Match<Object> route) throws ServletException, IOException {
    try {
      if (response.isCommitted()) {
        LOGGER.warn("Failed to render an error page (response already committed).");
        return; // We can't render an error page if the response is committed.
      }
  
      response.reset();
      response.addHeader("Content-Type", "text/html; charset=UTF-8"); 
      
      // For built-in exception types (e.g. 404 Not Found):
      ModelAndView view = exceptionViews.produce(error.getClass(), error, request, response);
      if (view != null) {
        response.setStatus(HTTPStatus.fromException(error).getCode());
        internalTemplateEngine.render(view.viewName, view.viewModel, response.getWriter());
        return;
      }
      
      // For all other exception types:
      if (config.enableDebugMode) {
        // Show the debug screen in debug mode.
        debugScreen.handle(error, context(request, response), route);
      } else {
        // Otherwise show a generic internal server error page.
        response.setStatus(HTTPStatus.INTERNAL_SERVER_ERROR.getCode());
        view = exceptionViews.produce(InternalServerErrorException.class, error, request, response);
        internalTemplateEngine.render(view.viewName, view.viewModel, response.getWriter());
      }
    } catch (Throwable e2) {      
      // This should be very rare.
      response.setStatus(HTTPStatus.INTERNAL_SERVER_ERROR.getCode());
      response.getWriter().println("500 INTERNAL SERVER ERROR");
    }
  }
  
  private void logRequest(HttpServletRequest request, String target) {
    if (config.enableDebugMode) {
      LOGGER.info("REQUEST ({}): {} {} -> {}",
          request.getRemoteAddr(),
          request.getMethod().toUpperCase(),
          request.getPathInfo(),
          target);
    }
  }
  
  private boolean acceptWebSocket(HttpServletRequest request,
                                 HttpServletResponse response,
                                 Match<Object> route) throws IOException {
    if (!(route.getData() instanceof LightningWebSocketCreator)) {
      return false;
    }
    
    LightningWebSocketCreator creator = (LightningWebSocketCreator)route.getData();
    
    logRequest(request, "@WebSocket " + creator.getType().getCanonicalName());
    
    if (!webSocketFactory.isUpgradeRequest(request, response)) {
      throw new BadRequestException();
    }
    
    // The request is consumed regardless of what acceptWebSocket returns.
    // If it fails, the factory commits the response with an error code.
    webSocketFactory.acceptWebSocket(creator, request, response);
    return true;
  }
  
  private boolean sendStaticFile(HttpServletRequest request,
                                 HttpServletResponse response) throws ServletException, IOException {
    if (config.server.staticFilesPath != null &&
        request.getMethod().equalsIgnoreCase("GET") && 
        fileServer.couldConsume(request, response)) {
      logRequest(request, "STATIC FILE");
      fileServer.handle(request, response);
      return true;
    }
    
    return false;
  }
  
  private boolean redirectInsecureRequest(HttpServletRequest request, 
                                          HttpServletResponse response) throws URISyntaxException, IOException {
    if (!config.ssl.isEnabled() ||
        !config.ssl.redirectInsecureRequests ||
        !request.getScheme().equalsIgnoreCase("https")) {
      return false;
    }
    
    logRequest(request, "HTTPS REDIRECT");
    URI httpUri = new URI(request.getRequestURL().toString());
    URI httpsUri = new URI("https",
                           httpUri.getUserInfo(),
                           httpUri.getHost(),
                           config.ssl.port,
                           httpUri.getPath(),
                           httpUri.getQuery(),
                           null);
    
    response.sendRedirect(httpsUri.toString());
    return true;
  }
  
  private synchronized void rescan() throws Exception {
    scanResult = scanner.scan();
    
    // Exception Handlers
    {
      exceptionHandlers.clear();
      for (Class<?> clazz : scanResult.exceptionHandlers.keySet()) {
        for (Method method : scanResult.exceptionHandlers.get(clazz)) {
          ExceptionHandler annotation = method.getAnnotation(ExceptionHandler.class);
          exceptionHandlers.map(annotation.value(), method);
        }
      }
    }
    
    // Routes & Web Sockets
    {
      routes.clear();
      
      // Built-ins.
      {
        DebugMapController.map(routes, config);
        HealthScreenController.map(routes, config);
      }
      
      // @Route annotations.
      {
        for (Class<?> clazz : scanResult.routes.keySet()) {
          for (Method method : scanResult.routes.get(clazz)) {
            if (method.getAnnotation(Route.class) != null) {
              Route route = method.getAnnotation(Route.class);
              for (HTTPMethod httpMethod : route.methods()) {
                routes.map(httpMethod, route.path(), method);
              }
            }
            else if (method.getAnnotation(Routes.class) != null) {
              for (Route route : method.getAnnotation(Routes.class).value()) {
                for (HTTPMethod httpMethod : route.methods()) {
                  routes.map(httpMethod, route.path(), method);
                }
              }
            }
          }
        }
      }
      
      // TODO: Drop active web socket connections immediately when code changes (instead of on next event).
      // TODO: Add tighter integration between framework and web sockets (e.g. Context.* properties).
      // @WebSocket annotations.
      {
        Map<String, LightningWebSocketCreator> oldSingletonWebSockets = singletonWebSockets;
        singletonWebSockets = config.enableDebugMode ? new HashMap<>() : null;

        for (Class<?> clazz : scanResult.websockets) {
          @SuppressWarnings("unchecked")
          Class<? extends WebSocketHandler> ws = (Class<? extends WebSocketHandler>)clazz;
          WebSocket info = clazz.getAnnotation(WebSocket.class);
          LightningWebSocketCreator creator = null;

          // Handler code can be reloaded in debug mode.
          // For singleton web sockets, we are clearing the route entries which means we create
          // a new singleton on each rescan. We need to instead use the old singleton unless the
          // code or path for that singleton has changed.
          if (config.enableDebugMode) {
            LightningWebSocketCreator oldCreator = oldSingletonWebSockets.get(info.path());
            if (oldCreator != null &&
                oldCreator.getType().getCanonicalName() == clazz.getCanonicalName() &&
                !oldCreator.isCodeChanged()) {
              creator = oldCreator;
            }
          }

          if (creator == null) {
            creator = new LightningWebSocketCreator(config, 
                                                    new Injector(globalInjectorModule, userInjectorModule), 
                                                    ws, 
                                                    info.isSingleton());
          }

          if (config.enableDebugMode && info.isSingleton()) {
            singletonWebSockets.put(info.path(), creator);
          }

          routes.map(HTTPMethod.GET,
                          info.path(),
                          creator);
        }
      }
      
      routes.compile();
    }
    
    // Filters
    {
      filters.clear();
      
      for (Class<?> clazz : scanResult.beforeFilters.keySet()) {
        for (Method m : scanResult.beforeFilters.get(clazz)) {
          if (m.getAnnotation(Before.class) != null) {
            Before info = m.getAnnotation(Before.class);
            filters.addFilterBefore(info.path(), info.methods(), info.priority(), m);
          }
          else if (m.getAnnotation(Before.class) != null) {
            for (Before info : m.getAnnotation(Befores.class).value()) {
              filters.addFilterBefore(info.path(), info.methods(), info.priority(), m);
            }
          }
        }
      }
    }
  }

  @Override
  public void handle(String target, 
                     org.eclipse.jetty.server.Request baseRequest, 
                     HttpServletRequest sRequest,
                     HttpServletResponse sResponse) throws IOException, ServletException {   
    Match<Object> route = null;
    baseRequest.setHandled(true);
    
    try {
      if (config.enableDebugMode) {
        rescan(); // Reloads all routes, exception handlers, filters, etc.
      }
      
      if (redirectInsecureRequest(sRequest, sResponse)) {
        return;
      }
      
      if (sendStaticFile(sRequest, sResponse)) {
        return;
      }
      
      route = routes.lookup(sRequest);
      
      if (route != null) {
        if (acceptWebSocket(sRequest, sResponse, route)) {
          return;
        }
        
        if (config.server.enableOutputBuffering) {
          sResponse = new BufferingHttpServletResponse(sResponse, config, outputBufferMatcher);
        }
        
        if (processRoute(sRequest, sResponse, route)) {
          return;
        }
      }
      
      logRequest(sRequest, "NOT FOUND");
      throw new NotFoundException();
    } catch (Throwable error) {
      if (!INTERNAL_EXCEPTIONS.contains(error.getClass())) {
        LOGGER.warn("A request handler returned an exception: ", error);
      }
      
      sendErrorPage(sRequest, sResponse, error, route);
    } finally {
      HandlerContext context = (HandlerContext)sRequest.getAttribute(HandlerContext.ATTRIBUTE);
      
      if (context != null && !context.isAsync()) {
        context.close();
        sRequest.removeAttribute(HandlerContext.ATTRIBUTE);
      }

      MultiPartInputStreamParser multipartInputStream = (MultiPartInputStreamParser)sRequest.getAttribute(
          org.eclipse.jetty.server.Request.__MULTIPART_INPUT_STREAM);
      if (multipartInputStream != null) {
        if (!sRequest.isAsyncStarted()) {
          try {
            multipartInputStream.deleteParts();
          } catch (MultiException e){
            LOGGER.warn("Error cleaning multiparts:", e);
          }
        } else if (context == null || !context.isAsync()) {
          // If you get this error, it's because you invoked request().raw().startAsync() instead of goAsync on
          // lightning.server.Context.
          LOGGER.warn("Using servlet async with multipart request may not clean pieces - use Lightning's goAsync instead.");
        }
      }
      
      Context.clearContext();
    }
  }
  
  private void runControllerInitializers(HandlerContext context, Object controller) throws Throwable {
    assert (controller != null);
    
    Class<?> currentClass = controller.getClass();

    while (currentClass != null) {
      if (scanResult.initializers.containsKey(currentClass)) {
        for (Method i : scanResult.initializers.get(currentClass)) {
          i.invoke(controller, context.injector().getInjectedArguments(i));
        }
      }

      currentClass = currentClass.getSuperclass();
    }
  }
  
  private void runControllerFinalizers(HandlerContext context, Object controller) {
    assert (controller != null);
    Class<?> currentClass = controller.getClass();

    while (currentClass != null) {
      if (scanResult.finalizers.containsKey(currentClass)) {
        for (Method i : scanResult.finalizers.get(currentClass)) {
          try {
            i.invoke(controller, context.injector().getInjectedArguments(i));
          } catch (Throwable e) {
            LOGGER.error("An error occured executing a finalizer {}: {}", i, e);
          }
        }
      }

      currentClass = currentClass.getSuperclass();
    }
  }
  
  private void processControllerOutput(HandlerContext context, 
                                       Method target, 
                                       Object output) throws Throwable {
    assert (output != null);
    
    Json json = target.getAnnotation(Json.class);
    if (json != null) {
     context.sendJson(output, json.prefix(), json.names());
     return;
    }

    if (output instanceof ModelAndView) {
      context.render((ModelAndView)output);
      return;
    }
    
    Template template = target.getAnnotation(Template.class);
    if (template != null) {
      if (template.value() != null) {
        context.render(template.value(), output);
        return;
      }
      
      throw new LightningException("Improper use of @Template annotation - refer to documentation.");
    }
    
    if (output instanceof File) {
      context.sendFile((File)output);
      return;
    }
   
    if (output instanceof String) {
      context.response.write((String)output);
      return;
    }
    
    throw new LightningException("Unable to process return value of @Route - refer to documentation.");
  }
  
  private void processBeforeFilters(HandlerContext context) throws Throwable {
    FilterMatch<Method> filters = this.filters.lookup(context.request.path(), 
                                                      context.request.method());

    for (lightning.routing.FilterMapper.Filter<Method> filter : filters.beforeFilters()) {
      ((InternalRequest)context.request).setWildcards(filter.wildcards(context.request.path()));
      ((InternalRequest)context.request).setParams(filter.params(context.request.path()));
      filter.handler.invoke(null, context.injector().getInjectedArguments(filter.handler));
    }
  }
  
  private void processBeforeAnnotationFilters(HandlerContext context, Method target) throws Throwable {
    if (target.getAnnotation(Filters.class) != null) {
      for (Filter filter : target.getAnnotation(Filters.class).value()) {
        RouteFilter instance = context.injector().newInstance(filter.value());
        instance.execute();
      }
    } else if (target.getAnnotation(Filter.class) != null) {
      RouteFilter instance = context.injector().newInstance(target.getAnnotation(Filter.class).value());
      instance.execute();
    }
  }
  
  private boolean processRoute(HttpServletRequest request, 
                               HttpServletResponse response, 
                               Match<Object> route) throws Throwable {
    if (!(route.getData() instanceof Method)) {
      return false;
    }
    
    Method target = (Method)route.getData();
    Object controller = null;
    HandlerContext context = context(request, response);
    logRequest(request, "@Route " + target.toString());
    
    if (context.request.isMultipart()) {
      if (!config.server.multipartEnabled) {
        throw new BadRequestException("Multipart requests are disallowed.");
      }

      request.setAttribute(
          org.eclipse.jetty.server.Request.__MULTIPART_CONFIG_ELEMENT,
          new MultipartConfigElement(
              config.server.multipartSaveLocation,
              config.server.multipartPieceLimitBytes,
              config.server.multipartRequestLimitBytes,
              config.server.multipartPieceLimitBytes));
    }
    
    try {
      // Set the default content type for all route targets.
      response.setContentType("text/html; charset=UTF-8");
      
      // Execute @Before filters.
      processBeforeFilters(context);

      ((InternalRequest)context.request).setWildcards(route.getWildcards());
      ((InternalRequest)context.request).setParams(route.getParams());
      
      // Perform pre-processing.
      if (target.getAnnotation(Multipart.class) != null) {
        context.requireMultipart();
      }
      
      if (target.getAnnotation(RequireAuth.class) != null) {
        context.requireAuth();
      }
      
      {
        RequireXsrfToken info = target.getAnnotation(RequireXsrfToken.class);
        if (info != null) {
          context.requireXsrf(info.inputName());
        }
      }
      
      // Execute @Filter filters.
      processBeforeAnnotationFilters(context, target);
      
      // Instantiate the controller.
      controller = context.injector().newInstance(target.getDeclaringClass());
      
      // Run @Initializers.
      runControllerInitializers(context, controller);
      
      // Execute the @Route.
      Object output = target.invoke(controller, context.injector().getInjectedArguments(target));
      
      // Try to save the session here if we need to since post-processing may commit the response.
      context.maybeSaveSession();
      
      // Perform post-processing.
      if (output != null) {
        processControllerOutput(context, target, output);
      }
    } catch (HaltException e) {
      // A halt exception says to jump to here in the life cycle.
    } catch (InvocationTargetException e) { 
      if (e.getCause() != null) {
        // Leads to better error logs/debug screens.
        throw e.getCause();
      }
      
      throw e;
    } finally {
      // Run @Finalizers.
      if (controller != null) {
        runControllerFinalizers(context, controller);
      }
    }
    
    return true;
  }
  
  private HandlerContext context(HttpServletRequest request, HttpServletResponse response) {
    HandlerContext context = (HandlerContext)request.getAttribute(HandlerContext.ATTRIBUTE);
    
    if (context == null) {
      InternalRequest lRequest = InternalRequest.makeRequest(request, config.server.trustLoadBalancerHeaders);
      InternalResponse lResponse = InternalResponse.makeResponse(response);
      context = new HandlerContext(lRequest, lResponse, dbProvider, config, userTemplateEngine, fileServer, 
                                   mailer, jsonService, cache, globalInjectorModule, userInjectorModule);
      lRequest.setCookieManager(context.cookies);
      lResponse.setCookieManager(context.cookies);
      request.setAttribute(HandlerContext.ATTRIBUTE, context);
      Context.setContext(context);
    }
    
    return context;
  }
}