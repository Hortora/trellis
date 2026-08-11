package io.hortora.trellis.worklog;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Qualifier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.file.Files;
import java.nio.file.Path;

@ApplicationScoped
public class WorklogDataSourceProducer {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface WorklogDS {}

    @ConfigProperty(name = "trellis.worklog.db-path",
                    defaultValue = "${user.home}/.hortora/worklog.db")
    String dbPath;

    private DataSource dataSource;
    private boolean dbAvailable;
    private Path    resolvedDbPath;


    @PostConstruct
    void init() {
        var resolved = dbPath.replace("${user.home}", System.getProperty("user.home"));
        var path     = Path.of(resolved);
        this.resolvedDbPath = path;
        if (!Files.exists(path)) {
            dbAvailable = false;
            return;
        }
        var ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:file:" + path + "?mode=ro");
        ds.getConfig().setBusyTimeout(3000);
        this.dataSource  = ds;
        this.dbAvailable = true;
    }

    @Produces
    @ApplicationScoped
    @WorklogDS
    DataSource worklogDataSource() {
        return dataSource;
    }

    public boolean isDbAvailable() {
        return dbAvailable;
    }

    public Path getDbPath() {
        return resolvedDbPath;
    }

}
