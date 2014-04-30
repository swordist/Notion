package edu.mayo.qia.pacs;

import io.dropwizard.Application;
import io.dropwizard.db.DataSourceFactory;
import io.dropwizard.db.ManagedDataSource;
import io.dropwizard.hibernate.HibernateBundle;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;

import javax.sql.DataSource;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.session.SessionHandler;
import org.secnod.dropwizard.shiro.ShiroBundle;
import org.secnod.dropwizard.shiro.ShiroConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.bazaarvoice.dropwizard.assets.ConfiguredAssetsBundle;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.datatype.joda.JodaModule;

import edu.mayo.qia.pacs.components.Device;
import edu.mayo.qia.pacs.components.Instance;
import edu.mayo.qia.pacs.components.Item;
import edu.mayo.qia.pacs.components.MoveRequest;
import edu.mayo.qia.pacs.components.Pool;
import edu.mayo.qia.pacs.components.PoolContainer;
import edu.mayo.qia.pacs.components.PoolManager;
import edu.mayo.qia.pacs.components.Query;
import edu.mayo.qia.pacs.components.Result;
import edu.mayo.qia.pacs.components.Script;
import edu.mayo.qia.pacs.components.Series;
import edu.mayo.qia.pacs.components.Study;
import edu.mayo.qia.pacs.rest.PoolEndpoint;

public class NotionApplication extends Application<NotionConfiguration> {
  static Logger logger = LoggerFactory.getLogger(NotionApplication.class);
  static int HashIterations = 100;

  private final HibernateBundle<NotionConfiguration> hibernate = new HibernateBundle<NotionConfiguration>(Connector.class, Device.class, Instance.class, Item.class, MoveRequest.class, Pool.class, PoolContainer.class, PoolManager.class, Query.class,
      Result.class, Script.class, Series.class, Study.class) {
    @Override
    public DataSourceFactory getDataSourceFactory(NotionConfiguration configuration) {
      return configuration.getDataSourceFactory();
    }

    @Override
    protected void configure(org.hibernate.cfg.Configuration configuration) {
      super.configure(configuration);
      configuration.setProperty("hibernate.show_sql", "true");
      configuration.setProperty("show_sql", "true");
    }
  };

  private final ShiroBundle<NotionConfiguration> shiro = new ShiroBundle<NotionConfiguration>() {

    @Override
    protected ShiroConfiguration narrow(NotionConfiguration configuration) {
      return configuration.shiro;
    }
  };
  public static ManagedDataSource dataSource;

  @Override
  public void initialize(Bootstrap<NotionConfiguration> bootstrap) {
    bootstrap.addBundle(hibernate);
    bootstrap.addBundle(shiro);
    bootstrap.addBundle(new ConfiguredAssetsBundle("/public", "/", "index.html"));
  }

  @Override
  public void run(NotionConfiguration configuration, Environment environment) throws Exception {

    // Register Joda time
    environment.getObjectMapper().registerModule(new JodaModule());
    environment.getObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // init Spring context
    // before we init the app context, we have to create a parent context with
    // all the config objects others rely on to get initialized
    AnnotationConfigApplicationContext parent = new AnnotationConfigApplicationContext();
    parent.getBeanFactory().registerSingleton("executor", environment.lifecycle().executorService("Notion").build());
    parent.getBeanFactory().registerSingleton("dropwizardEnvironment", environment);
    parent.getBeanFactory().registerSingleton("sessionFactory", hibernate.getSessionFactory());
    parent.getBeanFactory().registerSingleton("configuration", configuration);
    dataSource = configuration.getDataSourceFactory().build(environment.metrics(), "Notion");
    parent.getBeanFactory().registerSingleton("dataSource", dataSource);
    parent.refresh();
    parent.registerShutdownHook();
    parent.start();
    for (String name : parent.getBeanDefinitionNames()) {
      System.out.println("Bean: " + name);
    }

    Object ds = parent.getBean("dataSource", DataSource.class);

    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context.setParent(parent);
    context.scan("edu.mayo.qia.pacs", "edu.mayo.qia.pacs.dicom");
    context.refresh();
    context.registerShutdownHook();
    context.start();

    String[] beans = parent.getBeanDefinitionNames();
    for (String name : context.getBeanDefinitionNames()) {
      System.out.println("Bean: " + name);
    }
    logger.debug(parent.getBeanDefinitionNames().toString());
    logger.info(parent.getBean("flyway").toString());

    environment.servlets().setSessionHandler(new SessionHandler());
    environment.jersey().setUrlPattern("/rest/*");

    // Add a component
    environment.jersey().register(context.getBean(PoolEndpoint.class));

  }
}
