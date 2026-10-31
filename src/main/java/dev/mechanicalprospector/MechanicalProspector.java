package dev.mechanicalprospector;

import com.mojang.logging.LogUtils;
import dev.mechanicalprospector.network.ProspectorNetwork;
import dev.mechanicalprospector.scan.ScanService;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

@Mod(MechanicalProspector.MOD_ID)
public final class MechanicalProspector {
    public static final String MOD_ID = "create_ore_prospector";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("create");
    public static final DeferredItem<ProspectorItem> PROSPECTOR = ITEMS.register("ore_prospector",
            () -> new ProspectorItem(new Item.Properties().stacksTo(1)));
    // Preserve instruments already present in older saves without duplicating the creative entry.
    public static final DeferredRegister.Items LEGACY_ITEMS = DeferredRegister.createItems("mechanical_prospector");
    public static final DeferredItem<ProspectorItem> LEGACY_PROSPECTOR = LEGACY_ITEMS.register("prospector",
            () -> new ProspectorItem(new Item.Properties().stacksTo(1)));
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> PROSPECTOR_TAB = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + MOD_ID))
                    .icon(() -> new ItemStack(PROSPECTOR.get()))
                    .withTabsAfter(CreativeModeTabs.TOOLS_AND_UTILITIES)
                    .displayItems((parameters, output) -> output.accept(PROSPECTOR.get()))
                    .build());

    public MechanicalProspector(IEventBus bus, ModContainer container) {
        ProspectorConfig.register(container);
        ITEMS.register(bus);
        LEGACY_ITEMS.register(bus);
        TABS.register(bus);
        bus.addListener(ProspectorNetwork::register);
        NeoForge.EVENT_BUS.addListener(ScanService::tick);
        NeoForge.EVENT_BUS.addListener(ScanService::stopped);
        NeoForge.EVENT_BUS.addListener(ScanService::dataReload);
    }

    public static boolean isProspector(ItemStack stack) {
        return stack.is(PROSPECTOR.get()) || stack.is(LEGACY_PROSPECTOR.get());
    }
}
