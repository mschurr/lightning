package lightning.ann;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Inherited
/**
 * Controller classes (annotated with @Controller) will have any methods
 * annotated with @Initializer invoked before receiving any traffic.
 * 
 * Initializer methods are injectable (see @Route).
 */
public @interface Initializer {}
