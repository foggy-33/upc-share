package cn.upcshare.downloadsite.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private String resourcesDir = "../resources";
    private String jwtSecret = "dev-secret-change-me-dev-secret-change-me";
    private String nodeName = "campus";
    private boolean cookieSecure = false;
    private boolean scanResourcesOnStartup = true;
    private Migration migration = new Migration();

    public String getResourcesDir() { return resourcesDir; }
    public void setResourcesDir(String resourcesDir) { this.resourcesDir = resourcesDir; }
    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
    public String getNodeName() { return nodeName; }
    public void setNodeName(String nodeName) { this.nodeName = nodeName; }
    public boolean isCookieSecure() { return cookieSecure; }
    public void setCookieSecure(boolean cookieSecure) { this.cookieSecure = cookieSecure; }
    public boolean isScanResourcesOnStartup() { return scanResourcesOnStartup; }
    public void setScanResourcesOnStartup(boolean scanResourcesOnStartup) { this.scanResourcesOnStartup = scanResourcesOnStartup; }
    public Migration getMigration() { return migration; }
    public void setMigration(Migration migration) { this.migration = migration; }

    public static class Migration {
        private boolean enabled = false;
        private String sqlitePath = "../data/files.db";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getSqlitePath() { return sqlitePath; }
        public void setSqlitePath(String sqlitePath) { this.sqlitePath = sqlitePath; }
    }
}
