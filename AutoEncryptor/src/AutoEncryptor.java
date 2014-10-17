import java.nio.file.*;
import java.nio.file.WatchEvent.Kind;

import static java.nio.file.StandardWatchEventKinds.*;
import static java.nio.file.LinkOption.*;

import java.nio.file.attribute.*;
import java.io.*;
import java.util.*;

public class AutoEncryptor {

	public AutoEncryptor(Path dir, Path remoteDir) {
		// TODO Auto-generated constructor stub
	}

	private void processEvents() {
		// TODO Auto-generated method stub

	}

	public static void usage() {
		System.out.println("Usage information:");
	}

	public static void main(String[] args) {
		// parse arguments
		if (args.length != 2)
			usage();

		// register directory and remote and process its events
		Path dir = Paths.get(args[0]);
		Path remoteDir = Paths.get(args[1]);
		new AutoEncryptor(dir, remoteDir).processEvents();
	}

}
