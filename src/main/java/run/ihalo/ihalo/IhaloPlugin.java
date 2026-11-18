package run.ihalo.ihalo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * Plugin main class to manage the lifecycle of the plugin.
 *
 * <p>This class must be public and have a public constructor.
 *
 * <p>Only one main class extending {@link BasePlugin} is allowed per plugin.
 *
 * @author galaxy-eye
 * @since 1.0.0
 */
@Component
public class IhaloPlugin extends BasePlugin {

    private static final Logger log = LoggerFactory.getLogger(IhaloPlugin.class);

    public IhaloPlugin(PluginContext pluginContext) {
        super(pluginContext);
    }

    @Override
    public void start() {
        log.info("插件 ihalo 启动成功");
    }

    @Override
    public void stop() {
        log.info("插件 ihalo 已停止");
    }
}
