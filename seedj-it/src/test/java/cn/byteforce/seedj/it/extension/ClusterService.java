package cn.byteforce.seedj.it.extension;

import java.lang.annotation.*;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ClusterService {
    String value();
}