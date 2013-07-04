package atomicstryker.ruins.common;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Random;

import net.minecraft.world.biome.BiomeGenBase;

public class RuinHandler {
	private final static int COUNT = 0, WEIGHT = 1, CHANCE = 2;
	private ArrayList<HashSet<RuinIBuildable>> templates = new ArrayList<HashSet<RuinIBuildable>>();
	private ArrayList<Exclude> excluded = new ArrayList<Exclude>();
	protected int[][] vars;
	
	protected int triesPerChunkNormal = 6, chanceToSpawnNormal = 10, chanceForSiteNormal = 15,
				  triesPerChunkNether = 6, chanceToSpawnNether = 10, chanceForSiteNether = 15;
	public boolean loaded = false;
	public boolean disableLogging;
	public File saveFolder;

	public RuinHandler( File worldPath ) {
		// create the vars array fitting to the number of Biomes present
		int biomeAmountPlusOne = RuinsMod.BIOME_NONE+1;
		vars = new int[3][biomeAmountPlusOne];
		for ( int j = 0; j < vars[0].length; j++ )
		{
			vars[CHANCE][j] = 75;
		}
		
		saveFolder = worldPath;
		
		// fill up the template arraylist
		for( int fill = 0; fill < biomeAmountPlusOne; fill++ ) {
			templates.add( new HashSet<RuinIBuildable>() );
		}

		PrintWriter pw;
		File basedir = null;
		try {
			basedir = RuinsMod.getMinecraftBaseDir();
			basedir = new File ( basedir, "mods" );
		} catch( Exception e ) {
			System.err.println( "Could not access the main Minecraft mods directory; error: "+e );
			System.err.println( "The ruins mod could not be loaded." );
			e.printStackTrace();
			return;
		}
		try {
			File log = new File( basedir, "ruins_log.txt" );
			if( log.exists() ) {
				log.delete();
				log.createNewFile();
			}
			pw = new PrintWriter( new BufferedWriter( new FileWriter( log ) ) );
		} catch( Exception e ) {
			System.err.println( "There was an error when creating the log file." );
			System.err.println( "The ruins mod could not be loaded." );
			e.printStackTrace();
			return;
		}

		File templPath = new File( basedir, "resources" );
		templPath = new File( templPath, "ruins" );
		if( ! templPath.exists() ) {
			System.out.println( "Could not access the resources path for the ruins templates, file doesn't exist!" );
			System.err.println( "The ruins mod could not be loaded." );
			pw.close();
			return;
		}

		try {
			// load in the generic templates
			pw.println( "Loading the generic ruins templates..." );
			addRuins( pw, new File(templPath, "generic"), RuinsMod.BIOME_NONE );
			vars[COUNT][RuinsMod.BIOME_NONE] = templates.get( RuinsMod.BIOME_NONE ).size();
			recalcBiomeWeight( RuinsMod.BIOME_NONE );
		} catch( Exception e ) {
			printErrorToLog( pw, e, "There was an error when loading the generic ruins templates:" );
		}
		
		// dynamic Biome config loader, gets all information straight from BiomeGenBase
		for (int x = 0; x < BiomeGenBase.biomeList.length; x++)
		{
		    if (BiomeGenBase.biomeList[x] != null)
		    {
	            try
	            {
	                loadSpecificTemplates( pw, templPath, BiomeGenBase.biomeList[x].biomeID, BiomeGenBase.biomeList[x].biomeName );
	                pw.println("Loaded "+BiomeGenBase.biomeList[x].biomeName+" ruins templates, biomeID "+BiomeGenBase.biomeList[x].biomeID);
	            }
	            catch( Exception e )
	            {
	                printErrorToLog( pw, e, "There was an error when loading the "+BiomeGenBase.biomeList[x].biomeName+" ruins templates:" );
	            }
		    }
		}
		
		// Find and load the excluded file.  If this does not exist, no worries.
		try {
			pw.println();
			pw.println( "Loading excluded list from: " + worldPath.getCanonicalPath() );
			readExclusions( worldPath, pw );
		} catch( Exception e ) {
			pw.println( "No exclusions found for this world." );
		}


		// Now load in the main options file.  All of these will revert to defaults if
		// the file could not be loaded.
		try {
			pw.println();
			pw.println( "Loading options from: " + worldPath.getCanonicalPath() );
			readGlobalOptions( worldPath );
		} catch( Exception e ) {
			printErrorToLog( pw, e, "There was an error when loading the options file.  Defaults will be used instead." );
		}
		
		new CustomRotationMapping(templPath, pw);
		
		pw.println( "Ruins mod loaded." );
		pw.flush();
		pw.close();
		loaded = true;
	}

	public RuinIBuildable getTemplate( Random random, int biome ) {
		try {
			int rand = random.nextInt( vars[WEIGHT][biome] );
			int oldval = 0, increment = 0;
			RuinIBuildable retval = null;
			Iterator<RuinIBuildable> i = templates.get( biome ).iterator();
			while( i.hasNext() ) {
				retval = i.next();
				increment += retval.getWeight();
				if( ( oldval <= rand ) && ( rand < increment ) ) {
					return retval;
				}
				oldval += retval.getWeight();
			}
			return retval;
		} catch( Exception e ) {
			return null;
		}
	}

	public boolean useGeneric( Random random, int biome ) {
		if( biome == RuinsMod.BIOME_NONE ) { return true; }
		if( random.nextInt( 100 )+1 < vars[CHANCE][biome] ) { return false; }
		return true;
	}

	public void removeTemplate( RuinIBuildable r, int biome ) {
		// removes a ruin from the specified biome, providing
		// support for unique templates.
		if( templates.get( biome ).contains( r ) ) {
			templates.get( biome ).remove( r );
			excluded.add( new Exclude( r.getName(), biome ) );
			recalcBiomeWeight( biome );
		}
	}

	public void removeTemplate( String name, int biome ) {
		// removes a ruin from the specified biome, providing
		// support for unique templates.
		Iterator<RuinIBuildable> i = templates.get( biome ).iterator();
		RuinIBuildable rem = null;
		boolean found = false;
		while( i.hasNext() ) {
			rem = i.next();
			if( rem.getName().equals( name ) ) {
				found = true;
				break;
			}
		}
		if( found ) {
			templates.get( biome ).remove( rem );
			excluded.add( new Exclude( name, biome ) );
			recalcBiomeWeight( biome );
		}
	}

	public void writeExclusions( File dir ) throws Exception {
        File file = new File( dir, "excl.txt" );
		PrintWriter pw = new PrintWriter( new BufferedWriter( new FileWriter( file ) ) );
		Iterator<Exclude> i = excluded.iterator();
		while( i.hasNext() ) {
			pw.println( i.next().toString() );
		}
		pw.flush();
		pw.close();
	}

	private void loadSpecificTemplates( PrintWriter pw, File dir, int biome, String bname ) throws Exception {
		pw.println();
		bname = bname.toLowerCase();
		pw.println( "Loading the " + bname + " ruins templates..." );
		pw.flush();
		File path_biome = new File( dir, bname );
		addRuins( pw, path_biome, biome );
		vars[COUNT][biome] = templates.get( biome ).size();
		recalcBiomeWeight( biome );
	}

	private void printErrorToLog( PrintWriter pw, Exception e, String msg ) {
		pw.println();
		pw.println( msg );
		e.printStackTrace( pw );
		pw.flush();
	}

	private void recalcBiomeWeight( int biome ) {
		Iterator<RuinIBuildable> i = templates.get( biome ).iterator();
		vars[WEIGHT][biome] = 0;
		while( i.hasNext() ) {
			vars[WEIGHT][biome] += i.next().getWeight();
		}
	}

	private void readGlobalOptions( File dir ) throws Exception {
        File file = new File( dir, "ruins.txt" );
		if( ! file.exists() ) {	
			RuinsMod.copyGlobalOptionsTo( dir );
		}
        BufferedReader br = new BufferedReader( new FileReader( file ) );
        String read = br.readLine();
        String[] check;
        while( read != null ) {
        	check = read.split( "=" );
            if( check[0].equals( "tries_per_chunk_normal" ) ) {
                triesPerChunkNormal = Integer.parseInt( check[1] );
            }
            if( check[0].equals( "chance_to_spawn_normal" ) ) {
                chanceToSpawnNormal = Integer.parseInt( check[1] );
            }
            if( check[0].equals( "chance_for_site_normal" ) ) {
                chanceForSiteNormal = Integer.parseInt( check[1] );
            }
            if( check[0].equals( "tries_per_chunk_nether" ) ) {
                triesPerChunkNether = Integer.parseInt( check[1] );
            }
            if( check[0].equals( "chance_to_spawn_nether" ) ) {
                chanceToSpawnNether = Integer.parseInt( check[1] );
            }
            if( check[0].equals( "chance_for_site_nether" ) ) {
                chanceForSiteNether = Integer.parseInt( check[1] );
            }            
            if( check[0].equals( "disableRuinSpawnCoordsLogging" ) ) {
				disableLogging = Boolean.parseBoolean(check[1]);
            }
            
            if ( read.startsWith( "specific_" ) )
            {
            	read = read.split("_")[1];
            	check = read.split( "=" );
            	boolean found = false;
                for ( int i = 0; i < BiomeGenBase.biomeList.length; i++ )
                {
                	if ( BiomeGenBase.biomeList[i] != null && BiomeGenBase.biomeList[i].biomeName.equalsIgnoreCase( check[0] ) )
                	{
                		vars[CHANCE][i] = Integer.parseInt( check[1] );
                		System.out.println("Parsed config line ["+read+"], vars[CHANCE]["+i+"] set to "+Integer.parseInt( check[1] ));
                		found = true;
                		break;
                	}
                }
                
                if (!found)
                {
                	System.out.println("Did not find Matching Biome for config string: ["+check[0]+"]");
                }
            }
			
            read = br.readLine();
        }
		br.close();
	}

	private void readExclusions( File dir, PrintWriter pw ) throws Exception {
        File file = new File( dir, "excl.txt" );
        BufferedReader br = new BufferedReader( new FileReader( file ) );
        String read = br.readLine();
        String[] check;
        while( read != null ) {
            if( read.startsWith( "excl=" ) ) {
				check = read.split( "=" );
				check = check[1].split( ";" );
                int biome = Integer.parseInt( check[0] );
				removeTemplate( check[1], biome );
				pw.println( "Excluded from biome " + BiomeGenBase.biomeList[biome].biomeName + ": " + check[1] );
            }
			read = br.readLine();
		}
		br.close();
	}

    private void addRuins( PrintWriter pw, File path, int biomeID ) throws Exception {
        HashSet<RuinIBuildable> targetList = templates.get(biomeID);
        RuinIBuildable r;
        if (path.listFiles() != null)
        {
            for( File f : path.listFiles() ) {
                try {
    				switch( checkFileType( f.getName() ) ) {
    				case RuinsMod.FILE_TEMPLATE:
    					r = new RuinTemplate( f.getCanonicalPath() );
    					targetList.add( r );
    					
    					String candidate;
    					for (String biomeName : ((RuinTemplate)r).getBiomesToSpawnIn()) {
    					    for (int x = 0; x < BiomeGenBase.biomeList.length; x++) {
    					        if (BiomeGenBase.biomeList[x] != null) {
    					            candidate = BiomeGenBase.biomeList[x].biomeName.toLowerCase();
    					            if (candidate.equals(biomeName)) {
    					                if (BiomeGenBase.biomeList[x].biomeID != biomeID) {
    					                    templates.get(x).add( r );
    					                }
    					                break;
    					            }
    					        }
    					    }
    					}
    					
    					pw.println( "Successfully loaded template " + f.getName() + " with weight " + r.getWeight() + "." );
    					break;
    				case RuinsMod.FILE_COMPLEX:
    					r = null;
    					pw.println( "Successfully loaded complex " + f.getName() + " with no weight " );
    					break;
    				default:
    					if( ! f.isDirectory() ) { pw.println( "Ignoring unknown file type: " + f.getName() ); }
    					break;
    				}
                } catch( Exception e ) {
    				pw.println();
    				pw.println( "There was a problem loading the file: " + f.getName() );
    				e.printStackTrace( pw );
                }
            }
        }
        else
        {
        	pw.println( "Did not find any Building data for "+path+", creating empty folder for it: "+(path.mkdir() ? "success" : "failed") );
        }
		pw.flush();
    }

    private static int checkFileType( String s ) {
		int mid = s.lastIndexOf( "." );
		String ext = s.substring( mid + 1, s.length() );
		if( ext.equals( RuinsMod.TEMPLATE_EXT ) ) { return RuinsMod.FILE_TEMPLATE; }
		if( ext.equals( RuinsMod.COMPLEX_EXT ) ) { return RuinsMod.FILE_COMPLEX; }
        return -1;
    }

	private class Exclude {
		protected String name;
		protected int biome;

		public Exclude( String n, int b ) {
			name = n;
			biome = b;
		}

		public String toString() {
			return "excl=" + biome + ";" + name;
		}
	}
}