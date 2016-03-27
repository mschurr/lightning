package lightning.ann;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import lightning.enums.HTTPMethod;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(Routes.class)
/**
 * Use to indicate that a given method on a @Controller
 * should handle traffic on the given path for the given
 * methods.
 * 
 * The path may contain parameters or wild cards.
 * 
 * Example Paths:
 *  /
 *  /my/path/
 *  /u/:username
 *  /static/*
 *  
 * Wildcards and parameters contained in the request will
 * be exposed on the Request in the handler.
 *  request.getWildcards()
 *  request.getWildcardPath()
 *  request.routeParam("name")
 * 
 * Routes will be automatically installed based on the 
 * presence of these annotations.
 * 
 * Static files take precedence over routes.
 * 
 * Routes must be instance methods and declared public.
 * 
 * Routes are matched such that priority is given first
 * to more concrete routes. For example, consider the
 * following routes:
 *  /a/*
 *  /a/:user
 *  /a/bob
 *  
 * For these incoming URLs, the following routes will be
 * executed:
 *  /a/hello -> /a/:user
 *  /a/hello/world -> /a/*
 *  /a/bob -> /a/bob
 *  
 * Concrete path segments take the highest priority, followed
 * by parametric segments, followed by wildcards.
 */
public @interface Route {
  // The path to match (see above for details).
  String path();
  
  // The HTTP methods accepted.
  HTTPMethod[] methods() default {HTTPMethod.GET};
}