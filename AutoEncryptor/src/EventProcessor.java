import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;


public interface EventProcessor {

	void processEvent(WatchKey key, WatchEvent<?> event);
	void initialize();
	
}
