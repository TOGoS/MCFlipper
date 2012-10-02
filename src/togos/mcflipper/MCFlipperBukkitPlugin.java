package togos.mcflipper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class MCFlipperBukkitPlugin extends JavaPlugin implements Listener
{
	static class FlipperData implements Comparable<FlipperData> {
		final String name;
		final String worldName;
		final long x, y, z;
		
		public FlipperData( String name, String worldName, long x, long y, long z ) {
			this.name = name;
			this.worldName = worldName;
			this.x = x; this.y = y; this.z = z;
		}
		
		public FlipperData( String name, Location loc ) {
			this( name, loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ() );
		}
		
		public String toString() {
			return name+"\t"+worldName+"\t"+x+","+y+","+z;
		}
		
		static final Pattern FDPAT = Pattern.compile("([^\t]+)\t([^\t]+)\t([\\+\\-]?\\d+),([\\+\\-]?\\d+),([\\+\\-]?\\d+)");
		
		static FlipperData fromString( String fd ) {
			Matcher m = FDPAT.matcher(fd);
			if( m.matches() ) {
				return new FlipperData( m.group(1), m.group(2),
					Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)) );
			} else {
				return null;
			}
		}
		
		public Flipper toFlipper( Server s ) {
			World w = s.getWorld(worldName);
			if( w == null ) {
				s.getLogger().warning("Failed to get world '"+worldName+"' referenced by flipper '"+name+"'");
				return null;
			}
			return new Flipper( name, new Location(w,x,y,z) );
		}
		
		@Override public int compareTo(FlipperData o) {
			return name.compareTo(o.name);
		}
	}
	
	static class Flipper {
		public final FlipperData dat;
		public final Location loc;
		
		public Flipper( String name, Location l ) {
			assert( l != null );
			this.loc = l;
			this.dat = new FlipperData( name, l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ() );
		}
		
		public boolean getState() {
			return (loc.getBlock().getData() & 0x08) != 0;
		}
		
		public boolean setState( boolean s ) {
			Block b = loc.getBlock();
			int flags = b.getData();
			if( s ) {
				flags |=  0x08;
			} else {
				flags &= ~0x08;
			}
			b.setData( (byte)flags );
			return true;
		}
	}
	
	class FlipperState {
		public final String flipperName;
		public final boolean state;
		
		public FlipperState( String id, boolean state ) {
			this.flipperName = id;
			this.state = state;
		}
	}
	
	boolean modifiedSinceSave = false;
	HashMap<String,FlipperData> flipperData = new HashMap<String,FlipperData>();
	HashMap<String,List<FlipperState>> stateSets = new HashMap<String,List<FlipperState>>();
	
	@Override public void onEnable() {
		super.onEnable();
		getServer().getPluginManager().registerEvents(this, this);
		load();
	}
	
	@Override public void onDisable() {
		super.onDisable();
	}
	
	public void load() {
		flipperData.clear();
		stateSets.clear();
		File dataDir = this.getDataFolder();
		File flipperFile = new File(dataDir+"/flippers.txt");
		if( !flipperFile.exists() ) {
			getLogger().info(flipperFile+" does not existi; skipping loading.");
			return;
		}
		
		getLogger().info("Loading flipper data from "+flipperFile+"...");
		
		try {
			BufferedReader r = new BufferedReader( new FileReader( flipperFile ) );
			String line;
			
			while( (line = r.readLine()) != null ) {
				if( line.isEmpty() ) continue;
				
				FlipperData fd = FlipperData.fromString(line);
				if( fd != null ) {
					flipperData.put( fd.name, fd );
				} else {
					getLogger().warning("Failed to load flipper line: "+line);
				}
			}
		} catch( IOException e ) {
			getLogger().warning("Failed to load flipper data from "+flipperFile+"; "+e.getMessage());
		}
		
		File ssFile = new File(dataDir+"/statesets.txt");
		if( ssFile.exists() ) try {
			BufferedReader r = new BufferedReader( new FileReader(ssFile) );
			List<FlipperState> cSet = null;
			String line;
			
			int lineNumber = 0;
			while( (line = r.readLine()) != null ) {
				++lineNumber;
				if( line.isEmpty() ) continue;
				
				if( line.startsWith("=set ") ) {
					stateSets.put( line.substring(5), cSet = new ArrayList<FlipperState>() );
				} else {
					int sa = line.lastIndexOf(' ');
					if( sa == -1 ) sa = line.lastIndexOf('\t');
					if( sa != -1 ) {
						if( cSet != null ) {
							String name = line.substring(0, sa).trim();
							String state = line.substring(sa).trim();
							if( "on".equals(state) ) {
								cSet.add(new FlipperState(name, true ));
							} else if( "off".equals(state) ) {
								cSet.add(new FlipperState(name, false));
							} else {
								getLogger().warning("Invalid state: '"+state+"' in "+ssFile+":"+lineNumber);
							}
						} else {
							getLogger().warning("No set name given before state line in "+ssFile+":"+lineNumber+": "+line);
						}
					} else {
						getLogger().warning("Unrecognised line in "+ssFile+":"+lineNumber+": "+line);
					}
				}
			}
			getLogger().info("Loaded "+stateSets.size()+" state sets from "+ssFile);
		} catch( IOException e ) {
			getLogger().warning("Failed to load flipper state sets from "+ssFile+"; "+e.getMessage());
		} else {
			getLogger().info("No state sets loaded; "+ssFile+" does not exist");
		}
		
		getLogger().info("Done loading flipper data.");
		modifiedSinceSave = false;
	}
	
	/*
	@EventHandler
	public void onLoadWorld( WorldLoadEvent wle ) {
		load();
	}
	*/
	
	public void save() {
		if( !modifiedSinceSave ) {
			return;
		}
		File dataDir = this.getDataFolder();
		File flipperFile = new File(dataDir+"/flippers.txt");
		
		getLogger().info("Saving flipper data to "+flipperFile);
		
		if( !dataDir.exists() ) dataDir.mkdirs();

		ArrayList<FlipperData> flipperDats = new ArrayList<FlipperData>();
		for( Iterator<FlipperData> i=flipperData.values().iterator(); i.hasNext(); ) {
			flipperDats.add( i.next() );
		}
		Collections.sort(flipperDats);
		
		try {
			FileWriter ff = new FileWriter(flipperFile);
			for( Iterator<FlipperData> i=flipperDats.iterator(); i.hasNext(); ) {
				ff.write( i.next()+"\n" );
			}
			ff.close();
			modifiedSinceSave = false;
		} catch( IOException e ) {
			getLogger().warning("Failed to open "+flipperFile+" for saving; "+e.getMessage());
		}
		
		getLogger().info("Done saving flipper data.");
	}
	
	@EventHandler
	public void onSaveWorld( WorldSaveEvent wse ) {
		save();
	}
	
	protected void setFlipper( FlipperData dat ) {
		flipperData.put( dat.name, dat );
		modifiedSinceSave = true;
	}
	
	protected Flipper getFlipper( String name ) {
		FlipperData fd = flipperData.get(name);
		if( fd == null ) return null;
		return fd.toFlipper(getServer());
	}
	
	public boolean onCommand( CommandSender sender, Command cmd, String label, String[] args ) {
		String cmdName = cmd.getName();
		if( "reload-flippers".equals(cmdName) ) {
			load();
			return true;
		} else 	if( "warp-to-flipper".equals(cmdName) ) {
			if( args.length != 1 ) {
				sender.sendMessage("Usage: "+label+" <flipper-name>");
				return false;
			}
			if( !(sender instanceof Player) ) {
				sender.sendMessage("You must be a player to warp-to-flipper!");
				return false;
			}
			String flipperName = args[0];
			Flipper f = getFlipper(flipperName);
			if( f == null ) {
				sender.sendMessage("No such flipper: '"+flipperName+"'");
				return false;
			}
			Player p = (Player)sender;
			p.teleport( f.loc );
			return true;
		} else if( "set-flipper".equals(cmdName) ) {
			if( args.length < 1 ) {
				sender.sendMessage("Usage: "+label+" <flipper-name> [{on|off}]");
				return false;
			}
			boolean state;
			if( args.length == 1 ) {
				state = true;
			} else if( "on".equalsIgnoreCase(args[1]) ) {
				state = true;
			} else if( "off".equalsIgnoreCase(args[1]) ) {
				state = false;
			} else {
				sender.sendMessage("Invalid flipper state: '"+args[1]+"' (try 'on' or 'off')");
				return false;
			}
			String flipperName = args[0];
			Flipper f = getFlipper(flipperName);
			if( f != null ) {
				return f.setState(state);
			}
			List<FlipperState> s = stateSets.get(flipperName);
			if( s != null ) {
				if( state ) {
					for( FlipperState fs : s ) {
						f = getFlipper(fs.flipperName);
						if( f == null ) {
							sender.sendMessage("Warning: state set "+flipperName+" refers to non-existent flipper '"+fs.flipperName+"'");
						} else {
							f.setState(fs.state);
						}
					}
					return true;
				} else {
					sender.sendMessage("Can only flip a state set 'on'");
					return false;
				}
			}
			sender.sendMessage("No such flipper or state set: '"+flipperName+"'");
			return false;
		} else if( "list-flippers".equals(cmdName) ) {
			sender.sendMessage("Flippers:");
			for( Iterator<FlipperData> i=flipperData.values().iterator(); i.hasNext(); ) {
				sender.sendMessage( "  "+i.next().toString() );
			}
			sender.sendMessage("Flipper state sets:");
			for( Iterator<String> i=stateSets.keySet().iterator(); i.hasNext(); ) {
				sender.sendMessage( "  "+i.next() );
			}
			return true;
		} else if( "make-flipper".equals(cmdName) ) {
			String flipperName = null;
			String worldName = null;
			int x=0, y=0, z=0;
			if( args.length == 5 ) {
				flipperName = args[0];
				worldName = args[1];
			} else if( args.length == 1 && (sender instanceof Player) ) {
				Player p = (Player)sender;
				flipperName = args[0];
				Location l = p.getLocation();
				worldName = l.getWorld().getName();
				x = l.getBlockX(); y = l.getBlockY(); z = l.getBlockZ();
			}
			if( flipperName == null ) {
				sender.sendMessage("No flipper name given.");
				return false;
			}
			if( worldName == null ) {
				sender.sendMessage("No world name given.");
				return false;
			}
			setFlipper( new FlipperData(flipperName, worldName, x, y, z) );
			return true;
		} else {
			sender.sendMessage(this.getClass().getName()+" does not handle "+cmdName);
			return false;
		}
	}
}
