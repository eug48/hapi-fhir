package ca.uhn.fhir.jpa.provider.r4;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.util.*;
import java.util.concurrent.TimeUnit;

import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.hl7.fhir.r4.hapi.rest.server.GraphQLProvider;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Patient;
import org.junit.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.context.ContextLoader;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.*;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.DispatcherServlet;

import ca.uhn.fhir.jpa.config.r4.WebsocketR4Config;
import ca.uhn.fhir.jpa.config.r4.WebsocketR4DispatcherConfig;
import ca.uhn.fhir.jpa.dao.data.ISearchDao;
import ca.uhn.fhir.jpa.dao.r4.BaseJpaR4Test;
import ca.uhn.fhir.jpa.dao.r4.SearchParamRegistryR4;
import ca.uhn.fhir.jpa.subscription.r4.RestHookSubscriptionR4Interceptor;
import ca.uhn.fhir.jpa.search.DatabaseBackedPagingProvider;
import ca.uhn.fhir.jpa.search.ISearchCoordinatorSvc;
import ca.uhn.fhir.jpa.validation.JpaValidationSupportChainR4;
import ca.uhn.fhir.narrative.DefaultThymeleafNarrativeGenerator;
import ca.uhn.fhir.parser.StrictErrorHandler;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.client.interceptor.LoggingInterceptor;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.interceptor.CorsInterceptor;
import ca.uhn.fhir.util.PortUtil;
import ca.uhn.fhir.util.TestUtil;

public abstract class BaseResourceProviderR4Test extends BaseJpaR4Test {

	protected static JpaValidationSupportChainR4 myValidationSupport;
	protected static IGenericClient ourClient;
	protected static CloseableHttpClient ourHttpClient;
	protected static int ourPort;
	protected static RestfulServer ourRestServer;
	private static Server ourServer;
	protected static String ourServerBase;
	private static GenericWebApplicationContext ourWebApplicationContext;
	private TerminologyUploaderProviderR4 myTerminologyUploaderProvider;
	protected static SearchParamRegistryR4 ourSearchParamRegistry;
	protected static DatabaseBackedPagingProvider ourPagingProvider;
	protected static RestHookSubscriptionR4Interceptor ourRestHookSubscriptionInterceptor;
	protected static ISearchDao mySearchEntityDao;
	protected static ISearchCoordinatorSvc mySearchCoordinatorSvc;
	private Object ourGraphQLProvider;

	public BaseResourceProviderR4Test() {
		super();
	}

	@After
	public void after() throws Exception {
		myFhirCtx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.ONCE);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Before
	public void before() throws Exception {
		myFhirCtx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
		myFhirCtx.getRestfulClientFactory().setSocketTimeout(1200 * 1000);
		myFhirCtx.setParserErrorHandler(new StrictErrorHandler());

		if (ourServer == null) {
			ourPort = PortUtil.findFreePort();

			ourRestServer = new RestfulServer(myFhirCtx);

			ourServerBase = "http://localhost:" + ourPort + "/fhir/context";

			ourRestServer.setResourceProviders((List) myResourceProviders);

			ourRestServer.getFhirContext().setNarrativeGenerator(new DefaultThymeleafNarrativeGenerator());

			myTerminologyUploaderProvider = myAppCtx.getBean(TerminologyUploaderProviderR4.class);
			ourGraphQLProvider = myAppCtx.getBean("myGraphQLProvider");

			ourRestServer.setPlainProviders(mySystemProvider, myTerminologyUploaderProvider, ourGraphQLProvider);

			JpaConformanceProviderR4 confProvider = new JpaConformanceProviderR4(ourRestServer, mySystemDao, myDaoConfig);
			confProvider.setImplementationDescription("THIS IS THE DESC");
			ourRestServer.setServerConformanceProvider(confProvider);

			ourPagingProvider = myAppCtx.getBean(DatabaseBackedPagingProvider.class);

			Server server = new Server(ourPort);

			ServletContextHandler proxyHandler = new ServletContextHandler();
			proxyHandler.setContextPath("/");

			ServletHolder servletHolder = new ServletHolder();
			servletHolder.setServlet(ourRestServer);
			proxyHandler.addServlet(servletHolder, "/fhir/context/*");

			ourWebApplicationContext = new GenericWebApplicationContext();
			ourWebApplicationContext.setParent(myAppCtx);
			ourWebApplicationContext.refresh();
			proxyHandler.getServletContext().setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, ourWebApplicationContext);

			DispatcherServlet dispatcherServlet = new DispatcherServlet();
			// dispatcherServlet.setApplicationContext(webApplicationContext);
			dispatcherServlet.setContextClass(AnnotationConfigWebApplicationContext.class);
			ServletHolder subsServletHolder = new ServletHolder();
			subsServletHolder.setServlet(dispatcherServlet);
			subsServletHolder.setInitParameter(
					ContextLoader.CONFIG_LOCATION_PARAM, 
					WebsocketR4Config.class.getName() + "\n" + 
					WebsocketR4DispatcherConfig.class.getName());
			proxyHandler.addServlet(subsServletHolder, "/*");

			// Register a CORS filter
			CorsConfiguration config = new CorsConfiguration();
			CorsInterceptor corsInterceptor = new CorsInterceptor(config);
			config.addAllowedHeader("x-fhir-starter");
			config.addAllowedHeader("Origin");
			config.addAllowedHeader("Accept");
			config.addAllowedHeader("X-Requested-With");
			config.addAllowedHeader("Content-Type");
			config.addAllowedHeader("Access-Control-Request-Method");
			config.addAllowedHeader("Access-Control-Request-Headers");
			config.addAllowedOrigin("*");
			config.addExposedHeader("Location");
			config.addExposedHeader("Content-Location");
			config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
			ourRestServer.registerInterceptor(corsInterceptor);

			server.setHandler(proxyHandler);
			server.start();

			WebApplicationContext wac = WebApplicationContextUtils.getWebApplicationContext(subsServletHolder.getServlet().getServletConfig().getServletContext());
			myValidationSupport = wac.getBean(JpaValidationSupportChainR4.class);
			mySearchCoordinatorSvc = wac.getBean(ISearchCoordinatorSvc.class);
			mySearchEntityDao = wac.getBean(ISearchDao.class);
			ourRestHookSubscriptionInterceptor = wac.getBean(RestHookSubscriptionR4Interceptor.class);
			ourSearchParamRegistry = wac.getBean(SearchParamRegistryR4.class);

			myFhirCtx.getRestfulClientFactory().setSocketTimeout(5000000);
			ourClient = myFhirCtx.newRestfulGenericClient(ourServerBase);
			if (shouldLogClient()) {
				ourClient.registerInterceptor(new LoggingInterceptor());
			}
			
			PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager(5000, TimeUnit.MILLISECONDS);
			HttpClientBuilder builder = HttpClientBuilder.create();
			builder.setConnectionManager(connectionManager);
			builder.setMaxConnPerRoute(99);
			ourHttpClient = builder.build();

			ourServer = server;
		}

		ourRestServer.setPagingProvider(ourPagingProvider);
	}

	protected boolean shouldLogClient() {
		return true;
	}

	protected List<String> toNameList(Bundle resp) {
		List<String> names = new ArrayList<String>();
		for (BundleEntryComponent next : resp.getEntry()) {
			Patient nextPt = (Patient) next.getResource();
			String nextStr = nextPt.getName().size() > 0 ? nextPt.getName().get(0).getGivenAsSingleString() + " " + nextPt.getName().get(0).getFamily() : "";
			if (isNotBlank(nextStr)) {
				names.add(nextStr);
			}
		}
		return names;
	}

	@AfterClass
	public static void afterClassClearContextBaseResourceProviderR4Test() throws Exception {
		ourServer.stop();
		ourHttpClient.close();
		ourServer = null;
		ourHttpClient = null;
		myValidationSupport.flush();
		myValidationSupport = null;
		ourWebApplicationContext.close();
		ourWebApplicationContext = null;
		TestUtil.clearAllStaticFieldsForUnitTest();
	}

}
