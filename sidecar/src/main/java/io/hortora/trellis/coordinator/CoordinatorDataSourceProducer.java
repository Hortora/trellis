package io.hortora.trellis.coordinator;

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
import java.sql.SQLException;

@ApplicationScoped
public class CoordinatorDataSourceProducer {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface CoordinatorDS {}

    @ConfigProperty(name = "trellis.coordinator.db-path", defaultValue = "${user.home}/.trellis/coordinator.db")
    String dbPath;

    private DataSource dataSource;

    @PostConstruct
    void init() {
        try {
            var resolved = dbPath.replace("${user.home}", System.getProperty("user.home"));
            var path = Path.of(resolved);
            Files.createDirectories(path.getParent());
            var ds = new SQLiteDataSource();
            ds.setUrl("jdbc:sqlite:" + path);
            this.dataSource = ds;
            new CoordinatorSchemaManager().initialize(ds);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize coordinator database", e);
        }
    }

    @Produces
    @ApplicationScoped
    @CoordinatorDS
    DataSource coordinatorDataSource() {
        return dataSource;
    }
}
