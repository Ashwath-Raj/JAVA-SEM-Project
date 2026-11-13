import com.glyvex.commands.CommandAction;
import com.glyvex.commands.CommandRegistry;
import java.util.List;

public final class CommandRegistrySmokeTest {
    public static void main(String[] args) {
        CommandRegistry registry = new CommandRegistry();
        registry.setGlobalDryRun(true);
        registry.register("A", new CommandAction("APT", "Update package indexes", List.of("apt", "update"), false, true, false));
        registry.register("B", new CommandAction("PIPE", "List java processes", List.of("ps aux | grep java"), false, false, true));
        if (!registry.execute("A")) throw new AssertionError("admin dry-run mapping failed");
        if (!registry.execute("B")) throw new AssertionError("shell dry-run mapping failed");
        if (registry.execute("UNKNOWN")) throw new AssertionError("unknown mapping should not execute");
        registry.setEmergencyKillSwitch(true);
        if (registry.execute("A")) throw new AssertionError("kill switch should block execution");
        System.out.println("COMMAND REGISTRY SMOKE TEST PASS");
    }
}
