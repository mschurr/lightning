package lightning.examples.websockets;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A web socket is a singleton class which handles incoming requests statelessly through
 * event handlers.
 * TODO(mschurr): Unfortunately, Spark does not provide a way to utilize websockets with
 * constructors. Need to fix this.
 */
@WebSocket
public class ExampleWebsocket {
  private final static Logger logger = LoggerFactory.getLogger(ExampleWebsocket.class);
  
  public ExampleWebsocket() {
    logger.debug("Websocket Created.");
  }
  
  @OnWebSocketConnect
  public void connected(final Session session) {
    logger.debug("Connected: {}", session.getRemoteAddress().toString());
  }

  @OnWebSocketClose
  public void closed(final Session session, final int statusCode, final String reason) {
    logger.debug("Disconnected: {} ({} - {})", session.getRemoteAddress().toString(), statusCode, reason);
  }

  @OnWebSocketMessage
  public void message(final Session session, String message) {
    logger.debug("Received: {} -> {}", session.getRemoteAddress().toString(), message);
  }
  
  @OnWebSocketError
  public void error(final Session session, Throwable error) {
    logger.debug("Error: " + session.getRemoteAddress().toString(), error);
  }
}
