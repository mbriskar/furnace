package org.jboss.forge.furnace.services;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.enterprise.inject.spi.InjectionPoint;

import org.jboss.forge.furnace.addons.Addon;
import org.jboss.forge.furnace.addons.AddonRegistry;
import org.jboss.forge.furnace.util.AddonFilters;
import org.jboss.forge.furnace.util.Addons;
import org.jboss.forge.furnace.util.ClassLoaders;
import org.jboss.forge.proxy.ForgeProxy;
import org.jboss.forge.proxy.Proxies;

public class ExportedInstanceLazyLoader implements ForgeProxy
{
   private final Class<?> serviceType;
   private final AddonRegistry registry;
   private final InjectionPoint injectionPoint;
   private Object delegate;

   public ExportedInstanceLazyLoader(AddonRegistry registry, Class<?> serviceType, InjectionPoint injectionPoint)
   {
      this.registry = registry;
      this.serviceType = serviceType;
      this.injectionPoint = injectionPoint;
   }

   public static Object create(AddonRegistry registry, InjectionPoint injectionPoint, Class<?> serviceType)
   {
      ExportedInstanceLazyLoader callback = new ExportedInstanceLazyLoader(registry, serviceType,
               injectionPoint);
      return Proxies.enhance(serviceType, callback);
   }

   @Override
   public Object invoke(Object self, Method thisMethod, Method proceed, Object[] args) throws Throwable
   {
      try
      {
         if (thisMethod.getDeclaringClass().getName().equals(ForgeProxy.class.getName()))
         {
            return delegate;
         }
      }
      catch (Exception e)
      {
      }

      if (delegate == null)
         delegate = loadObject();

      return thisMethod.invoke(delegate, args);
   }

   private Object loadObject() throws Exception
   {
      Object result = null;
      for (Addon addon : registry.getAddons(AddonFilters.allLoaded()))
      {
         try
         {
            Addons.waitUntilStarted(addon, 1, TimeUnit.SECONDS);
            if (ClassLoaders.containsClass(addon.getClassLoader(), serviceType) && addon.getStatus().isStarted())
            {
               ServiceRegistry serviceRegistry = addon.getServiceRegistry();
               if (serviceRegistry.hasService(serviceType))
               {
                  ExportedInstance<?> instance = serviceRegistry.getExportedInstance(serviceType);
                  if (instance != null)
                  {
                     if (instance instanceof ExportedInstanceImpl)
                        // FIXME remove the need for this implementation coupling
                        result = ((ExportedInstanceImpl<?>) instance).get(new LocalServiceInjectionPoint(
                                 injectionPoint,
                                 serviceType));
                     else
                        result = instance.get();

                     if (result != null)
                        break;
                  }
               }
            }
         }
         catch (TimeoutException e)
         {
            // TODO for now, just give up after waiting for too long because we don't want to block forever.
         }
      }

      if (result == null)
      {
         throw new IllegalStateException("Remote service [" + serviceType.getName() + "] is not registered.");
      }

      return result;
   }

   @Override
   public Object getDelegate()
   {
      return delegate;
   }

}
