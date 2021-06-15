package org.tools.webfilesync;

import java.io.BufferedReader;


import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.attribute.BasicFileAttributes;

import static java.nio.file.LinkOption.*;
import static java.nio.file.StandardWatchEventKinds.*;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@Service
public class WatchService {
	
	@Autowired
	private SyncFileRepository repo;
	
	@Autowired
    private ApplicationArguments args;
	
	private static ApplicationContext ctx;
	
	private String basefolder;
	private Integer verbose; 
	private Integer maxthreads;
	private String fileurl;
	private Date updateDate;
	private String base64Creds;
	private List<Uploader> uploaders; // = new ArrayList<Uploader>();
	
	private java.nio.file.WatchService watcher;
	private Map<WatchKey,Path> keys;
	
	@Autowired
    private void setApplicationContext(ApplicationContext applicationContext) {
        ctx = applicationContext;       
    }
	
	public static List<Path> listDirectories(Path path) throws IOException {

        List<Path> result;
        try (Stream<Path> walk = Files.walk(path)) {
            result = walk.filter(Files::isDirectory)
                    .collect(Collectors.toList());
        }
        return result;

    }
	
	private void register(Path dir) throws IOException {
        WatchKey key = dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
        
        Path prev = keys.get(key);
        if (prev == null) {
            System.out.format("register: %s\n", dir);
        } else {
            if (!dir.equals(prev)) {
                System.out.format("update: %s -> %s\n", prev, dir);
            }
        }
        
        keys.put(key, dir);
    }
	
	private void registerAll(final Path start) throws IOException {
        // register directory and sub-directories
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                throws IOException
            {
                register(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
	
	public void synclocalfile(String basefolder,List<String> filters) {
		if(verbose>0) {
			System.out.println("Sync local file");
		}
		Path path = Paths.get(basefolder);
		List<Path> paths;		
		try {
			paths = listDirectories(path);
			paths.forEach(x -> {	
				try {
					register(x);
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			});
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	@SuppressWarnings("unchecked")
    static <T> WatchEvent<T> cast(WatchEvent<?> event) {
        return (WatchEvent<T>)event;
    }
	
	void processEvents() {
		System.out.println("Will start waiting");
        for (;;) {
 
            // wait for key to be signalled
            WatchKey key;
            try {
                key = watcher.take();
            } catch (InterruptedException x) {
                return;
            }
 
            Path dir = keys.get(key);
            if (dir == null) {
                System.err.println("WatchKey not recognized!!");
                continue;
            }
 
            for (WatchEvent<?> event: key.pollEvents()) {
                WatchEvent.Kind kind = event.kind();
 
                // TBD - provide example of how OVERFLOW event is handled
                if (kind == OVERFLOW) {
                    continue;
                }
 
                // Context for directory entry event is the file name of entry
                WatchEvent<Path> ev = cast(event);
                Path name = ev.context();
                Path child = dir.resolve(name);
 
                // print out event
                System.out.format("%s: %s\n", event.kind().name(), child);
 
                // if directory is created, and watching recursively, then
                // register it and its sub-directories
                if (kind == ENTRY_CREATE || kind == ENTRY_MODIFY) {
                    try {
                        if (Files.isDirectory(child, NOFOLLOW_LINKS)) {
                        	registerAll(child);
                        }
                        else {
                        	upload(child);
                        }
                    } catch (IOException x) {
                        // ignore to keep sample readbale
                    }
                }
                else if(kind == ENTRY_DELETE) {
                	delete(child);
                }
                	
            }
 
            // reset key and remove from set if directory no longer accessible
            boolean valid = key.reset();
            if (!valid) {
                keys.remove(key);
 
                // all directories are inaccessible
                if (keys.isEmpty()) {
                    break;
                }
            }
        }
        System.out.println("Done waiting");
    }
	
	public void delete(Path path) {
		System.out.println("Filename:" + path.getFileName());
		String absolutepath = path.toAbsolutePath().toString();
		System.out.println("Abs path:" + absolutepath);
		String relpath = "/";
		if(absolutepath.length()>basefolder.length()) {
			relpath = path.getParent().toAbsolutePath().toString().substring(basefolder.length());
		}
		System.out.println("Rel path:" + relpath);
		Long lastModified = new File(absolutepath).lastModified();
		
		SyncFile sfile = new SyncFile();
        sfile.setName(path.getFileName().toString());
		sfile.setPath(absolutepath);					
		sfile.setFolderPath(basefolder);
		sfile.setRelPath(relpath);
		sfile.setLastUpdate(lastModified);
		sfile.setLastChecked(updateDate);
		try {
			sfile.setSize(Files.size(Paths.get(absolutepath)));
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
			sfile.setSize((long) 0);
		}
		sfile.setOp("upload");
		sfile.setFileurl(fileurl);
				
		RestTemplate restTemplate = new RestTemplate();
	    HttpHeaders headers = new HttpHeaders();
	    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
	    headers.add("authorization", "Basic " + base64Creds);
	    		    
	    MultiValueMap<String, String> map= new LinkedMultiValueMap<>();
	    map.add("rel_path", sfile.getRelPath());
	    map.add("filename", sfile.getName());
	    map.add("op", "delete");
	    
	    HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(map, headers);
	    
	    ResponseEntity<String> response = restTemplate.postForEntity(fileurl, request , String.class);		
	}
	
	public void upload(Path path) {
		if(uploaders.size()>=maxthreads-1) {
			uploaders.forEach(up->{
				try {
					if(up.isAlive()) {
						up.join();
						uploaders.remove(up);
					}
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			});
		}
		
		String absolutepath = path.toAbsolutePath().toString();
		System.out.println("Abs path:" + absolutepath);
		String relpath = "/";
		if(absolutepath.length()>basefolder.length()) {
			relpath = path.getParent().toAbsolutePath().toString().substring(basefolder.length());
		}
		System.out.println("Rel path:" + relpath);
		Long lastModified = new File(absolutepath).lastModified();
        
        SyncFile sfile = new SyncFile();
        sfile.setName(path.getFileName().toString());
		sfile.setPath(absolutepath);					
		sfile.setFolderPath(basefolder);
		sfile.setRelPath(relpath);
		sfile.setLastUpdate(lastModified);
		sfile.setLastChecked(updateDate);
		try {
			sfile.setSize(Files.size(Paths.get(absolutepath)));
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
			sfile.setSize((long) 0);
		}
		sfile.setOp("upload");
		sfile.setFileurl(fileurl);
		
		if(uploaders.size()>=maxthreads-1) {
			System.out.println("Reached max threads count");
			uploaders.forEach(up->{
				System.out.println("Trying to rejoin up:" + up.toString());
				try {
					if(up.isAlive()) {
						System.out.println("Thread still alive");
						up.join();
						uploaders.remove(up);
					}
					else {
						System.out.println("Already dead");
						uploaders.remove(up);
						System.out.println("Done release that one");
					}
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			});
		}
        
		Uploader upload = ctx.getBean(Uploader.class); //new Uploader(base64creds, uf, verbose, fileurl, repo);		
		upload.setBase64creds(base64Creds);
		upload.setUf(sfile);
		upload.setVerbose(verbose);
		upload.setFileurl(fileurl);
		upload.start();
		uploaders.add(upload);		
		try {
			Thread.sleep(500);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	@PostConstruct
	public void init() {
		System.out.println("Starting the watch");
		basefolder = null;
		Long maxsize = (long) 50000000;
		fileurl = null;
		updateDate = new Date();
		verbose = 0;
		maxthreads = 20;
		List<String> filters = new ArrayList<String>();
		uploaders = new ArrayList<Uploader>();
		
		try {
			this.watcher = FileSystems.getDefault().newWatchService();
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
        this.keys = new HashMap<WatchKey,Path>();
		
		if(args.containsOption("basefolder")) 
        {
            //Get argument values
            List<String> values = args.getOptionValues("basefolder");
            try {	            	
				basefolder = values.get(0);				
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        }
		
		if(args.containsOption("verbose")) 
        {
            //Get argument values
            List<String> values = args.getOptionValues("verbose");
            try {	            	
				verbose = Integer.valueOf(values.get(0));				
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        }
		
		if(args.containsOption("maxthreads")) 
        {
            //Get argument values
            List<String> values = args.getOptionValues("maxthreads");
            try {	            	
            	maxthreads = Integer.valueOf(values.get(0));				
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        }
		
		if(args.containsOption("fileurl")) 
        {
            //Get argument values
            List<String> values = args.getOptionValues("fileurl");
            try {	            	
				fileurl = values.get(0);				
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        }
		
		if(args.containsOption("filtersfile")) 
        {
            //Get argument values
            List<String> values = args.getOptionValues("filtersfile");
            try {        	
				try (BufferedReader br = new BufferedReader(new FileReader(values.get(0)))) {
				    String line;
				    while ((line = br.readLine()) != null) {
				       // process the line.
				    	filters.add(line);
				    }
				}
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        }
		
		if(args.containsOption("maxsize")) 
        {
            //Get argument values
            List<String> values = args.getOptionValues("maxsize");
            try {	            	
				maxsize = Long.valueOf(values.get(0));				
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        }
		
		String plainCreds = "willie:p@ssword";
		
		if(args.containsOption("username") && args.containsOption("password")) 
        {
            //Get argument values
            List<String> unvalues = args.getOptionValues("username");
            List<String> passvalues = args.getOptionValues("password");
            try {	            	
				plainCreds = unvalues.get(0) + ":" + passvalues.get(0);	
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        }
		
		byte[] plainCredsBytes = plainCreds.getBytes();
		byte[] base64CredsBytes = Base64.getEncoder().encode(plainCredsBytes);		
		base64Creds = new String(base64CredsBytes);
		
		if(basefolder!=null) {
			System.out.println("Basefolder:" );
			System.out.println(basefolder);
			File bfile = new File(basefolder);
			if(bfile.exists()) {
				synclocalfile(basefolder,filters);
				if(fileurl!=null) {
					processEvents();
				}
			}
		}
		else {
			System.out.println("No basefolder not found");
		}
	}
}
