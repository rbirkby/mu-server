package io.muserver.handlers;

import io.muserver.MuHandlerBuilder;
import io.muserver.rest.RestHandlerBuilder;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;

import static io.muserver.handlers.ResourceType.DEFAULT_EXTENSION_MAPPINGS;

/**
 * <p>Used to create a {@link ResourceHandler} for serving static files.</p>
 * <p>In order to serve from the filesystem during development, and from classpath and deploy time, see
 * {@link #fileOrClasspath(String, String)}</p>
 */
public class ResourceHandlerBuilder implements MuHandlerBuilder<ResourceHandler> {

    private DateTimeFormatter directoryListingDateFormatter;
    private Map<String, ResourceType> extensionToResourceType = DEFAULT_EXTENSION_MAPPINGS;
    private String pathToServeFrom = "/";
    private String defaultFile = "index.html";
    private ResourceProviderFactory resourceProviderFactory;
    private boolean directoryListingEnabled = false;
    private String directoryListingCss = null;
    private ResourceCustomizer resourceCustomizer = null;

    /**
     * Specify custom filename extension to mime-type mappings. By default {@link ResourceType#DEFAULT_EXTENSION_MAPPINGS}
     * are used.
     * @param extensionToResourceType The mappings to use.
     * @return This builder
     */
    public ResourceHandlerBuilder withExtensionToResourceType(Map<String, ResourceType> extensionToResourceType) {
        this.extensionToResourceType = extensionToResourceType;
        return this;
    }

    /**
     * Specifies the path to serve the static from.
     * @param pathToServeFrom A path that static data should be served from. Defaults to <code>/</code>
     * @return This builder
     * @deprecated Please use <code>ContextHandlerBuilder.context(pathToServeFrom).addHandler(resourceHandler)</code> instead.
     */
    @Deprecated
    public ResourceHandlerBuilder withPathToServeFrom(String pathToServeFrom) {
        this.pathToServeFrom = pathToServeFrom;
        return this;
    }

    /**
     * Specifies the file to use when a request such as <code>/web/</code> is made. Defaults to <code>index.html</code>
     * @param defaultFile The default file to use when a directory is requested, or <code>null</code> for no default.
     * @return This builder
     */
    public ResourceHandlerBuilder withDefaultFile(String defaultFile) {
        this.defaultFile = defaultFile;
        return this;
    }

    ResourceHandlerBuilder withResourceProviderFactory(ResourceProviderFactory resourceProviderFactory) {
        this.resourceProviderFactory = resourceProviderFactory;
        return this;
    }

    /**
     * Specifies whether or not to allow directory listing. This is disabled by default.
     * <p>Note that directory listings will not show if there is a file in the directory
     * that matches the {@link #withDefaultFile(String)} setting. Set that to <code>null</code>
     * if you do not want the default file (e.g. <code>index.html</code>) to display.</p>
     * @param enabled <code>true</code> to turn it on; <code>false</code> to disable it.
     * @return This builder
     */
    public ResourceHandlerBuilder withDirectoryListing(boolean enabled) {
        this.directoryListingEnabled = enabled;
        return this;
    }

    /**
     * Specifies a custom date format for the "Last modified" column when directory listing is enabled.
     * @param dateTimeFormatter A format object, or null to use the default
     * @return This builder
     */
    public ResourceHandlerBuilder withDirectoryListingDateFormatter(DateTimeFormatter dateTimeFormatter) {
        this.directoryListingDateFormatter = dateTimeFormatter;
        return this;
    }

    /**
     * Specifies CSS to use for the HTML directory listing page, if directory listing is enabled.
     * @param css CSS styles to use, or null for the default
     * @return This builder
     */
    public ResourceHandlerBuilder withDirectoryListingCSS(String css) {
        this.directoryListingCss = css;
        return this;
    }

    /**
     * Registers a hook to intercept responses, allowing things such as response header customization based on the request.
     * @param resourceCustomizer A class to intercept responses
     * @return This builder
     */
    public ResourceHandlerBuilder withResourceCustomizer(ResourceCustomizer resourceCustomizer) {
        this.resourceCustomizer = resourceCustomizer;
        return this;
    }

    /**
     * Creates the handler
     * @return The built handler
     */
    public ResourceHandler build() {
        if (resourceProviderFactory == null) {
            throw new IllegalStateException("No resourceProviderFactory has been set");
        }
        String css = this.directoryListingCss;
        if (directoryListingEnabled && css == null) {
            InputStream cssStream = RestHandlerBuilder.class.getResourceAsStream("/io/muserver/resources/api.css");
            Scanner scanner = new Scanner(cssStream, "UTF-8").useDelimiter("\\A");
            css = scanner.next();
            scanner.close();
        }

        DateTimeFormatter formatterToUse = this.directoryListingDateFormatter;
        if (directoryListingEnabled && formatterToUse == null) {
            formatterToUse = DateTimeFormatter.ofPattern("yyyy/MM/dd hh:mm:ss")
                .withLocale(Locale.US)
                .withZone(ZoneId.systemDefault());
        }

        return new ResourceHandler(resourceProviderFactory, pathToServeFrom, defaultFile, extensionToResourceType, directoryListingEnabled, css, formatterToUse, this.resourceCustomizer);
    }


    /**
     * Creates a handler that serves files from the given directory.
     * @param directoryPath The directory.
     * @return A new builder.
     */
    public static ResourceHandlerBuilder fileHandler(String directoryPath) {
        return fileHandler(Paths.get(directoryPath));
    }

    /**
     * Creates a handler that serves files from the given directory.
     * @param baseDirectory The directory.
     * @return A new builder.
     */
    public static ResourceHandlerBuilder fileHandler(File baseDirectory) {
        return fileHandler(baseDirectory.toPath());
    }

    /**
     * Creates a handler that serves files from the given directory.
     * @param path The directory.
     * @return A new builder.
     */
    public static ResourceHandlerBuilder fileHandler(Path path) {
        return new ResourceHandlerBuilder().withResourceProviderFactory(ResourceProviderFactory.fileBased(path));
    }


    /**
     * Creates a handler that serves files from the classpath..
     * @param classpathRoot A classpath directory, such as <code>/web</code>
     * @return A new builder.
     */
    public static ResourceHandlerBuilder classpathHandler(String classpathRoot) {
        return new ResourceHandlerBuilder().withResourceProviderFactory(ResourceProviderFactory.classpathBased(classpathRoot));
    }

    /**
     * Creates a resource handler that serves from the file system if the directory exists; otherwise from the class path.
     * <p>
     * A common use case is for when you want to serve from the file path at development time (so you can update
     * files without restarting) but at deploy time resources are packaged in an uber jar.
     * @param fileRootIfExists A path to a directory holding static content, which may not exist, e.g. <code>src/main/resources/web</code>
     * @param classpathRoot A classpath path to a directory holding static content, e.g. <code>/web</code>
     * @return Returns a file-based resource handler builder or a classpath-based one.
     */
    public static ResourceHandlerBuilder fileOrClasspath(String fileRootIfExists, String classpathRoot) {
        Path path = Paths.get(fileRootIfExists);
        if (Files.isDirectory(path)) {
            return fileHandler(path);
        } else {
            return classpathHandler(classpathRoot);
        }
    }
}
