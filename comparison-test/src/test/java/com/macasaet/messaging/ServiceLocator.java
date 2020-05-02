package com.macasaet.messaging;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

public class ServiceLocator {

    private static ServiceLocator instance;

    @Autowired
    private ApplicationContext context;

    public ServiceLocator() {
        instance = this;
    }

    public static Object getMySpringBean() {
        return (Object)instance.context.getBean("mySpringBean");
    }
}