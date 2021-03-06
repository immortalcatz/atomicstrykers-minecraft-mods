package atomicstryker.ruins.common;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringEscapeUtils;

import com.google.common.collect.Lists;
import com.mojang.authlib.GameProfile;

import net.minecraft.block.Block;
import net.minecraft.block.IGrowable;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.item.EntityEnderCrystal;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTException;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntityCommandBlock;
import net.minecraft.tileentity.TileEntityMobSpawner;
import net.minecraft.tileentity.TileEntitySign;
import net.minecraft.tileentity.TileEntitySkull;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Rotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.loot.LootContext;
import net.minecraft.world.storage.loot.LootTable;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

public class RuinTemplateRule
{
    protected final Block[] blockIDs;
    protected final int[] blockMDs;
    protected final String[] blockStrings;
    protected final SpecialFlags[] specialFlags;
    protected final PreservePolicy[] preservePolicies;
    protected final int[] blockWeights;
    protected int blockWeightsTotal;
    private int chance = 100;
    private int condition = 0;
    final RuinTemplate owner;
    private final PrintWriter debugPrinter;
    private final boolean excessiveDebugging;

    // just leave the field null for NONE
    public enum SpecialFlags
    {
        COMMANDBLOCK, ADDBONEMEAL
    }

    private enum PreservePolicy { IGNORE, PRESERVE, CORRUPT }

    private static final Pattern patternInitialCommandBlock = Pattern.compile("(?:[1-9]\\d{0,4}\\*)?[?!]?CommandBlock:");
    private static final Pattern patternCommandBlockPrefix = Pattern.compile("(.*,)(?:([1-9]\\d{0,4})\\*)?([?!])?");
    private static final Pattern patternBlockPrefix = Pattern.compile("(?:([1-9]\\d{0,4})\\*)?([?!])?(.*)");

    public RuinTemplateRule(PrintWriter dpw, RuinTemplate r, String rule, boolean debug) throws Exception
    {
        debugPrinter = dpw;
        owner = r;
        excessiveDebugging = debug;

        ArrayList<String> nbttags = new ArrayList<>(5);
        rule = replaceNBTTags(rule, nbttags);

        String[] blockRules = rule.split(",");
        int numblocks = blockRules.length - 2;
        if (numblocks < 1)
        {
            throw new Exception("No blockIDs specified for rule [" + rule + "] in template " + owner.getName());
        }
        condition = Integer.parseInt(blockRules[0]);
        chance = Integer.parseInt(blockRules[1]);

        String[] data;

        // Command Block special case, contains basically any character that breaks this
        if (patternInitialCommandBlock.matcher(blockRules[2]).lookingAt())
        {
            String[] commandrules = rule.split("CommandBlock:");
            int count = commandrules.length - 1; // -1 because there is a prefix
                                                 // part we ignore

            // extract initial command block prefix, if any, from first field
            int blockWeight = 1;
            PreservePolicy preservePolicy = PreservePolicy.IGNORE;
            final Matcher matcher0 = patternCommandBlockPrefix.matcher(commandrules[0]);
            if (matcher0.matches())
            {
                if (matcher0.group(2) != null)
                {
                    blockWeight = Integer.parseInt(matcher0.group(2));
                }
                if (matcher0.group(3) != null)
                {
                    preservePolicy = matcher0.group(3).equals("?") ? PreservePolicy.PRESERVE : PreservePolicy.CORRUPT;
                }
                commandrules[0] = matcher0.group(1);
            }

            blockIDs = new Block[count];
            blockMDs = new int[count];
            blockStrings = new String[count];
            specialFlags = new SpecialFlags[count];
            preservePolicies = new PreservePolicy[count];
            blockWeights = new int[count];
            blockWeightsTotal = 0;
            for (int i = 0; i < count; i++)
            {
                // apply command block prefix from previous field
                blockWeightsTotal += blockWeights[i] = blockWeight;
                preservePolicies[i] = preservePolicy;
                // extract next command block prefix, if any (last field excluded)
                blockWeight = 1;
                preservePolicy = PreservePolicy.IGNORE;
                if (i < count - 1)
                {
                    final Matcher matcher = patternCommandBlockPrefix.matcher(commandrules[i + 1]);
                    if (matcher.matches())
                    {
                        if (matcher.group(2) != null)
                        {
                            blockWeight = Integer.parseInt(matcher.group(2));
                        }
                        if (matcher.group(3) != null)
                        {
                            preservePolicy = matcher.group(3).equals("?") ? PreservePolicy.PRESERVE : PreservePolicy.CORRUPT;
                        }
                        commandrules[i + 1] = matcher.group(1);
                    }
                }

                blockIDs[i] = null;
                blockMDs[i] = UNSPECIFIED_METADATA;
                // case meta value "-n" present (impulse command block)
                if (commandrules[i + 1].charAt(commandrules[i + 1].length() - 2) == '-')
                {
                    String meta = "" + commandrules[i + 1].charAt(commandrules[i + 1].length() - 1);
                    // needed because char to int conversion is bad here
                    blockMDs[i] = Integer.valueOf(meta);
                    // strip the last 2 chars from the string or else parsing
                    // the command will fail
                    commandrules[i + 1] = commandrules[i + 1].substring(0, commandrules[i + 1].length() - 3);
                }
                specialFlags[i] = SpecialFlags.COMMANDBLOCK;
                // readd the splitout string for the parsing, offset by 1
                // because of the prefix string
                blockStrings[i] = commandrules[i + 1];
                blockStrings[i] = restoreNBTTags(blockStrings[i], nbttags);
                if (excessiveDebugging)
                {
                    debugPrinter.println("template " + owner.getName() + " contains Command Block command: " + blockStrings[i] + " with meta: " + blockMDs[i] + ", weight: " + blockWeights[i] + ", preserve: " + preservePolicies[i]);
                }
            }
        }
        // not command blocks
        else
        {
            blockIDs = new Block[numblocks];
            blockMDs = new int[numblocks];
            blockStrings = new String[numblocks];
            specialFlags = new SpecialFlags[numblocks];
            preservePolicies = new PreservePolicy[numblocks];
            blockWeights = new int[numblocks];
            blockWeightsTotal = 0;
            for (int i = 0; i < numblocks; i++)
            {
                // extract block prefix, if any
                int blockWeight = 1;
                PreservePolicy preservePolicy = PreservePolicy.IGNORE;
                final Matcher matcher = patternBlockPrefix.matcher(blockRules[i + 2]);
                if (matcher.matches())
                {
                    if (matcher.group(1) != null)
                    {
                        blockWeight = Integer.parseInt(matcher.group(1));
                    }
                    if (matcher.group(2) != null)
                    {
                        preservePolicy = matcher.group(2).equals("?") ? PreservePolicy.PRESERVE : PreservePolicy.CORRUPT;
                    }
                    blockRules[i + 2] = matcher.group(3);
                }
                blockWeightsTotal += blockWeights[i] = blockWeight;
                preservePolicies[i] = preservePolicy;

                data = blockRules[i + 2].split("-");
                if (data.length > 1) // has '-' in it, like "torch-5" or
                                     // "planks-3"
                {
                    if (isNumber(data[0])) // 1-5
                    {
                        debugPrinter.println("Rule [" + rule + "] in template " + owner.getName() + " still uses numeric blockIDs! ERROR!");
                        blockIDs[i] = Blocks.AIR;
                        blockMDs[i] = UNSPECIFIED_METADATA;
                        blockStrings[i] = "";
                    }
                    else
                    // planks-3 or ChestGenHook:strongholdLibrary:5-2 or
                    // chisel:sandstone-scribbles-1
                    {
                        // compat cases: sand-scrib-1 or sand-scrib or
                        // d-d-derp-addbonemeal or even d-d-derp-3-addbonemeal
                        // need to rebuild data array
                        while (data.length > 1 && !isNumber(data[1]) && !data[1].equals("addbonemeal"))
                        {
                            String[] newdata = new String[data.length - 1];
                            newdata[0] = data[0] + "-" + data[1];
                            for (int j = 1; j < data.length - 1; j++)
                            {
                                newdata[j] = data[j + 1];
                            }
                            // this loop should keep concatting data[0] until
                            // data is [n, 3] or [n, addbonemeal] or [n, 3,
                            // addbonemeal]
                            // irregardless of how many '-' are contained in n
                            data = newdata;
                        }

                        blockStrings[i] = blockRules[i + 2];
                        blockStrings[i] = restoreNBTTags(blockStrings[i], nbttags);

                        blockIDs[i] = r.tryFindingBlockOfName(data[0]);
                        if (blockIDs[i] == r.getAirBlock())
                        {
                            if (!isAir(data[0]))
                            {
                                blockIDs[i] = null;
                                if (!isKnownSpecialRule(blockStrings[i]))
                                {
                                    throw new Exception(
                                            "Rule [" + rule + "], blockString [" + blockStrings[i] + "] in template " + owner.getName() + " can absolutely not be mapped to anything known");
                                }
                            }
                        }

                        // special case -addbonemeal
                        if (blockIDs[i] instanceof IGrowable && data[data.length - 1].equals("addbonemeal"))
                        {
                            specialFlags[i] = SpecialFlags.ADDBONEMEAL;
                            try
                            {
                                blockMDs[i] = Integer.parseInt(data[data.length - 2]);
                            }
                            catch (NumberFormatException ne)
                            {
                                blockMDs[i] = UNSPECIFIED_METADATA;
                            }
                        }
                        // otherwise parse meta value
                        else
                        {
                            try
                            {
                                blockMDs[i] = Integer.parseInt(data[data.length - 1]);
                            }
                            catch (NumberFormatException ne)
                            {
                                blockMDs[i] = UNSPECIFIED_METADATA;
                            }
                        }
                    }
                }
                else
                // does not have metadata specified, aka "50"
                {
                    if (isNumber(blockRules[i + 2]))
                    {
                        debugPrinter.println("Rule [" + rule + "] in template " + owner.getName() + " still uses numeric blockIDs! ERROR!");
                        blockIDs[i] = r.getAirBlock();
                        blockStrings[i] = "";
                    }
                    else
                    {
                        blockIDs[i] = r.tryFindingBlockOfName(blockRules[i + 2]);
                        if (blockIDs[i] == r.getAirBlock() && !isAir(blockRules[i + 2]))
                        {
                            // debugPrinter.println("Rule [" + rule + "] in
                            // template " + owner.getName()+" has something
                            // special? Checking again later");
                            blockIDs[i] = null;
                        }
                        blockStrings[i] = blockRules[i + 2];
                        blockStrings[i] = restoreNBTTags(blockStrings[i], nbttags);
                    }
                    blockMDs[i] = UNSPECIFIED_METADATA;
                }

                if (excessiveDebugging)
                {
                    debugPrinter.printf("rule alternative: %d, blockIDs[%s], blockMDs[%s], blockStrings[%s], specialflags:[%s], blockWeights:[%d], preservePolicies:[%s]\n", i + 1, blockIDs[i], blockMDs[i], blockStrings[i], specialFlags[i], blockWeights[i], preservePolicies[i]);
                }
            }
        }
    }

    RuinTemplateRule(PrintWriter dpw, RuinTemplate r, final String rule) throws Exception
    {
        this(dpw, r, rule, false);
    }

    /**
     * Since NBT contents, especially books, wreak havoc with all the legacy and
     * hardcoded string splitting going on, we replace them in their entirety
     * for slightly less problematic hardcoded strings.
     *
     * @param rule
     *            which may or may not contain nbt tags
     * @param nbttags
     *            a non null array list which after execution contains each nbt
     *            tag in order of occurence
     * @return the input string except all nbt tags have been replaced with
     *         NBT1, NBT2 ... etc
     */
    private String replaceNBTTags(String rule, ArrayList<String> nbttags)
    {
        int openingIndex = rule.indexOf('{');
        while (openingIndex != -1)
        {
            int closingIndex = openingIndex + 1;
            int bracketCounter = 1;
            for (;; closingIndex++)
            {
                if (closingIndex == rule.length())
                {
                    System.err.println("Unbalanced brackets in Ruins template, offending rule: " + rule);
                    return rule;
                }
                if (rule.charAt(closingIndex) == '{')
                {
                    bracketCounter++;
                }
                else if (rule.charAt(closingIndex) == '}')
                {
                    bracketCounter--;
                    if (bracketCounter == 0)
                    {
                        break;
                    }
                }
            }
            String capture = rule.substring(openingIndex, closingIndex + 1);
            nbttags.add(capture);
            if (excessiveDebugging)
            {
                debugPrinter.println("template " + owner.getName() + " contains nbt tag: " + capture);
            }
            String pre = rule.substring(0, openingIndex);
            String post = rule.substring(closingIndex + 1, rule.length());
            rule = pre + "NBT" + nbttags.size() + post;
            openingIndex = rule.indexOf('{');
        }
        return rule;
    }

    /** use regex so we dont get aliasing for NBT1, NBT10 etc */
    private final Pattern patternNBT = Pattern.compile("(NBT\\d*)");

    /**
     * And the reverse, restore the glorious NBT tags into a string loaded with
     * their placeholders
     *
     * @param str
     *            string with nbt placeholders
     * @param nbttags
     *            list with stored nbt tags
     * @return original string with nbt tags
     */
    private String restoreNBTTags(String str, ArrayList<String> nbttags)
    {
        Matcher matcher = patternNBT.matcher(str);
        StringBuffer sb = new StringBuffer(str.length());
        while (matcher.find())
        {
            String tag = matcher.group(0);
            // strip "NBT", get the number out
            int index = Integer.valueOf(tag.substring(3));
            // list index is 1 less
            matcher.appendReplacement(sb, nbttags.get(index - 1));
        }
        matcher.appendTail(sb);
        String result = sb.toString();
        return result;
    }

    @SuppressWarnings("unused")
    private boolean isNumber(String s)
    {
        if (s == null || s.equals(""))
        {
            return false;
        }
        try
        {
            int n = Integer.parseInt(s);
            return true;
        }
        catch (NumberFormatException e)
        {
            return false;
        }
    }

    public void doBlock(World world, Random random, int x, int y, int z, int rotate)
    {
        // check to see if we can create this block
        if (random.nextInt(100) < chance)
        {
            // we're cleared, pass it off to the correct conditional.
            switch (condition)
            {
            case 1:
                doAboveBlock(world, random, x, y, z, rotate);
                break;
            case 2:
                doAdjacentBlock(world, random, x, y, z, rotate);
                break;
            case 3:
                doUnderBlock(world, random, x, y, z, rotate);
                break;
            case -1:
                doAboveBlock(world, random, x, y, z, rotate);
                break;
            case -2:
                doAdjacentBlock(world, random, x, y, z, rotate);
                break;
            case -3:
                doUnderBlock(world, random, x, y, z, rotate);
                break;
            default:
                doNormalBlock(world, random, x, y, z, rotate);
                break;
            }
        }
    }

    public boolean runLater()
    {
        switch (condition <= 0 ? 0 - condition : condition)
        {
        case 1:
            return true; // Reorder
        case 2:
            return true; // Unchanged
        default:
            return false;

        }
    }

    public boolean runLast()
    {
        switch (condition <= 0 ? 0 - condition : condition)
        {
        case 3:
            return true; // Unchanged
        case 7:
            return true; // 7 for ALWAYS LAST
        default:
            return false;

        }
    }

    private void doNormalBlock(World world, Random random, int x, int y, int z, int rotate)
    {
        int blocknum = getBlockNum(random);
        handleBlockSpawning(world, random, x, y, z, blocknum, rotate, blockStrings[blocknum]);
    }

    private void doAboveBlock(World world, Random random, int x, int y, int z, int rotate)
    {
        BlockPos pos = new BlockPos(x, y - 1, z);
        if ((condition <= 0) ^ owner.isIgnoredBlock(world.getBlockState(pos).getBlock(), world, pos))
        {
            return;
        }
        int blocknum = getBlockNum(random);
        handleBlockSpawning(world, random, x, y, z, blocknum, rotate, blockStrings[blocknum]);
    }

    private void doAdjacentBlock(World world, Random random, int x, int y, int z, int rotate)
    {
        BlockPos a = new BlockPos(x + 1, y, z);
        BlockPos b = new BlockPos(x, y, z + 1);
        BlockPos c = new BlockPos(x, y, z - 1);
        BlockPos d = new BlockPos(x - 1, y, z);
        if ((condition <= 0) ^ (
        // Are -all- adjacent blocks air?
        (owner.isIgnoredBlock(world.getBlockState(a).getBlock(), world, a)) && (owner.isIgnoredBlock(world.getBlockState(b).getBlock(), world, b))
                && (owner.isIgnoredBlock(world.getBlockState(c).getBlock(), world, c)) && (owner.isIgnoredBlock(world.getBlockState(d).getBlock(), world, d))))
        {
            return;
        }
        int blocknum = getBlockNum(random);
        handleBlockSpawning(world, random, x, y, z, blocknum, rotate, blockStrings[blocknum]);
    }

    private void doUnderBlock(World world, Random random, int x, int y, int z, int rotate)
    {
        BlockPos pos = new BlockPos(x, y + 1, z);
        if ((condition <= 0) ^ owner.isIgnoredBlock(world.getBlockState(pos).getBlock(), world, pos))
        {
            return;
        }

        int blocknum = getBlockNum(random);
        handleBlockSpawning(world, random, x, y, z, blocknum, rotate, blockStrings[blocknum]);
    }

    private void handleBlockSpawning(World world, Random random, int x, int y, int z, int blocknum, int rotate, String blockString)
    {
        Block blockID = blockIDs[blocknum];
        BlockPos pos = new BlockPos(x, y, z);
        if (preservePolicies[blocknum] == PreservePolicy.IGNORE || (preservePolicies[blocknum] == PreservePolicy.PRESERVE) != owner.isIgnoredBlock(world.getBlockState(pos).getBlock(), world, pos))
        {
            if (excessiveDebugging)
            {
                debugPrinter.println("About to place blockID " + blockID + ", meta " + blockMDs[blocknum] + " preserve " + preservePolicies[blocknum] + " rotation " + rotate + ", string: " + blockString);
            }
            if (blockID == null)
            {
                doSpecialBlock(world, random, x, y, z, blocknum, rotate, StringEscapeUtils.unescapeJava(blockString));
            }
            else
            {
                placeBlock(world, blocknum, x, y, z, rotate);
            }
        }
        else
        {
            if (excessiveDebugging)
            {
                debugPrinter.println("Suppressing placement of blockID " + blockID + ", meta " + blockMDs[blocknum] + " preserve " + preservePolicies[blocknum] + " rotation " + rotate + ", string: " + blockString);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void placeBlock(World world, int blocknum, int x, int y, int z, int rotate)
    {
        realizeBlock(world, x, y, z, blockIDs[blocknum], blockMDs[blocknum], rotate);
        if (specialFlags[blocknum] != null)
        {
            switch (specialFlags[blocknum])
            {
            case ADDBONEMEAL:
                owner.markBlockForBonemeal(x, y, z);
                break;
            default:
                break;
            }
        }
    }

    private boolean isKnownSpecialRule(final String dataString)
    {
        if (dataString.equals("preserveBlock"))
        {
            return true;
        }
        else if (dataString.startsWith("MobSpawner:"))
        {
            return true;
        }
        else if (dataString.equals("UprightMobSpawn"))
        {
            return true;
        }
        else if (dataString.equals("EasyMobSpawn"))
        {
            return true;
        }
        else if (dataString.equals("MediumMobSpawn"))
        {
            return true;
        }
        else if (dataString.equals("HardMobSpawn"))
        {
            return true;
        }
        else if (dataString.startsWith("EasyChest"))
        {
            return true;
        }
        else if (dataString.startsWith("MediumChest"))
        {
            return true;
        }
        else if (dataString.startsWith("HardChest"))
        {
            return true;
        }
        else if (dataString.startsWith("ChestGenHook:"))
        {
            return true;
        }
        else if (dataString.startsWith("IInventory;"))
        {
            return tryFindingObject(dataString.split(";")[1]) instanceof Block;
        }
        else if (dataString.equals("EnderCrystal"))
        {
            return true;
        }
        else if (dataString.startsWith("CommandBlock:"))
        {
            return true;
        }
        else if (dataString.startsWith("StandingSign:"))
        {
            return true;
        }
        else if (dataString.startsWith("WallSign:"))
        {
            return true;
        }
        else if (dataString.startsWith("Skull:"))
        {
            return true;
        }
        else if (dataString.startsWith("teBlock;"))
        {
            return true;
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private void doSpecialBlock(World world, Random random, int x, int y, int z, int blocknum, int rotate, final String dataString)
    {
        if (dataString.equals("preserveBlock"))
        {
            // NOOP
        }
        else if (dataString.startsWith("MobSpawner:"))
        {
            addCustomSpawner(world, x, y, z, dataString.substring(dataString.indexOf(":") + 1));
        }
        else if (dataString.equals("UprightMobSpawn"))
        {
            addUprightMobSpawn(world, random, x, y, z);
        }
        else if (dataString.equals("EasyMobSpawn"))
        {
            addEasyMobSpawn(world, random, x, y, z);
        }
        else if (dataString.equals("MediumMobSpawn"))
        {
            addMediumMobSpawn(world, random, x, y, z);
        }
        else if (dataString.equals("HardMobSpawn"))
        {
            addHardMobSpawn(world, random, x, y, z);
        }
        else if (dataString.startsWith("EasyChest"))
        {
            addEasyChest(world, random, x, y, z, blockMDs[blocknum], rotate, random.nextInt(3) + 3);
        }
        else if (dataString.startsWith("MediumChest"))
        {
            addMediumChest(world, random, x, y, z, blockMDs[blocknum], rotate, random.nextInt(4) + 3);
        }
        else if (dataString.startsWith("HardChest"))
        {
            addHardChest(world, random, x, y, z, blockMDs[blocknum], rotate, random.nextInt(5) + 3);
        }
        else if (dataString.startsWith("ChestGenHook:"))
        {
            String[] s = dataString.split(":");
            int targetCount = s.length > 1 ? Integer.valueOf(s[2].split("-")[0]) : 0;
            addChestGenChest(world, random, x, y, z, s[1], targetCount, blockMDs[blocknum], rotate);
        }
        else if (dataString.startsWith("IInventory;"))
        {
            ArrayList<String> nbttags = new ArrayList<>(5);
            String dataWithoutNBT = replaceNBTTags(dataString, nbttags);
            String[] s = dataWithoutNBT.split(";");
            Object o = tryFindingObject(s[1]);
            if (o instanceof Block)
            {
                Block b = (Block) o;
                // need to strip meta '-x' value if present
                if (s[2].lastIndexOf("-") > s[2].length() - 5)
                {
                    addIInventoryBlock(world, random, x, y, z, b, s[2].substring(0, s[2].lastIndexOf("-")), nbttags, blockMDs[blocknum], rotate);
                }
                else
                {
                    addIInventoryBlock(world, random, x, y, z, b, s[2], nbttags, blockMDs[blocknum], rotate);
                }
            }
            else
            {
                System.err.println("Ruins Mod could not determine what IInventory block to spawn for [" + s[1] + "] in Ruin template: " + owner.getName());
            }
        }
        else if (dataString.equals("EnderCrystal"))
        {
            spawnEnderCrystal(world, x, y, z);
        }
        else if (specialFlags[blocknum] == SpecialFlags.COMMANDBLOCK)
        {
            int lastIdx = dataString.lastIndexOf(":");
            String sender;
            String command;
            if (lastIdx < 0)
            {
                command = dataString;
                sender = "@";
            }
            else
            {
                command = dataString.substring(0, lastIdx);
                sender = dataString.substring(lastIdx + 1, dataString.length());
            }
            addCommandBlock(world, x, y, z, command, sender, blockMDs[blocknum], rotate);
        }
        else if (dataString.startsWith("StandingSign:"))
        {
            TileEntitySign tes = (TileEntitySign) realizeBlock(world, x, y, z, Blocks.STANDING_SIGN, blockMDs[blocknum], rotate);
            if (tes != null && tes.signText != null)
            {
                String[] splits = dataString.split(":");
                for (int i = 0; i < tes.signText.length && i + 1 < splits.length; i++)
                {
                    tes.signText[i] = (splits[i + 1].split("-")[0].equals("null")) ? new TextComponentTranslation("") : new TextComponentTranslation(splits[i + 1].split("-")[0]);
                }
            }
        }
        else if (dataString.startsWith("WallSign:"))
        {
            TileEntitySign tes = (TileEntitySign) realizeBlock(world, x, y, z, Blocks.WALL_SIGN, blockMDs[blocknum], rotate);
            if (tes != null && tes.signText != null)
            {
                String[] splits = dataString.split(":");
                for (int i = 0; i < tes.signText.length && i + 1 < splits.length; i++)
                {
                    tes.signText[i] = (splits[i + 1].split("-")[0].equals("null")) ? new TextComponentTranslation("") : new TextComponentTranslation(splits[i + 1].split("-")[0]);
                }
            }
        }
        else if (dataString.startsWith("Skull:"))
        {
            // standard case Skull:2:8-3
            TileEntitySkull tes = (TileEntitySkull) realizeBlock(world, x, y, z, Blocks.SKULL, blockMDs[blocknum], rotate);
            if (tes != null)
            {
                String[] splits = dataString.split(":");
                ReflectionHelper.setPrivateValue(TileEntitySkull.class, tes, Integer.valueOf(splits[1]), 0);
                ReflectionHelper.setPrivateValue(TileEntitySkull.class, tes, Integer.valueOf(splits[2].split("-")[0]), 1);

                // is a player head saved?
                // looks like
                // Skull:3:8:1b4d8438-e714-3553-a433-059f2d3b1fd2-AtomicStryker-3
                if (splits.length > 3)
                {
                    // split segment like this:
                    // 1b4d8438-e714-3553-a433-059f2d3b1fd2-AtomicStryker-3
                    String[] moresplits = splits[3].split("-");
                    UUID id = UUID.fromString(moresplits[0] + "-" + moresplits[1] + "-" + moresplits[2] + "-" + moresplits[3] + "-" + moresplits[4]);
                    GameProfile playerprofile = new GameProfile(id, moresplits[5]);
                    ReflectionHelper.setPrivateValue(TileEntitySkull.class, tes, playerprofile, 2);
                }
                rotateTileEntity(tes, rotate);
            }
        }
        else if (dataString.startsWith("teBlock;"))
        {
            if (excessiveDebugging)
            {
                debugPrinter.println("teBlock about to be placed: " + dataString);
            }
            // examples: teBlock;minecraft:trapped_chest;{...nbt json...},
            // teBlock;minecraft:trapped_chest;{...nbt json...}-4
            String[] in = dataString.split(";");
            Block b = Block.REGISTRY.getObject(new ResourceLocation(in[1]));
            if (excessiveDebugging)
            {
                debugPrinter.println("teBlock object from [" + in[1] + "]: " + b);
            }
            if (b != Blocks.AIR)
            {
                BlockPos p = new BlockPos(x, y, z);
                try
                {
                    NBTTagCompound tc = JsonToNBT.getTagFromJson(in[2].substring(0, in[2].lastIndexOf('}') + 1));
                    if (excessiveDebugging)
                    {
                        debugPrinter.println("teBlock read, decoded nbt tag: " + tc.toString());
                    }
                    realizeBlock(world, x, y, z, b, blockMDs[blocknum], rotate);
                    world.removeTileEntity(p);
                    TileEntity tenew = TileEntity.create(world, tc);
                    rotateTileEntity(tenew, rotate);
                    world.setTileEntity(p, tenew);
                }
                catch (NBTException e)
                {
                    e.printStackTrace();
                }
            }
        }
        else
        {
            System.err.println("Ruins Mod could not determine what to spawn for [" + dataString + "] in Ruin template: " + owner.getName());
        }
    }

    private int getBlockNum(Random random)
    {
        // random selection using weights assigned in config file
        int blockIndex = 0;
        for (int selector = random.nextInt(blockWeightsTotal); (selector -= blockWeights[blockIndex]) >= 0; ++blockIndex);
        return blockIndex;
    }

    private void spawnEnderCrystal(World world, int x, int y, int z)
    {
        realizeBlock(world, x, y, z, Blocks.BEDROCK, UNSPECIFIED_METADATA, RuinsMod.DIR_NORTH);
        EntityEnderCrystal entityendercrystal = new EntityEnderCrystal(world);
        entityendercrystal.setLocationAndAngles((x + 0.5F), y, (z + 0.5F), world.rand.nextFloat() * 360.0F, 0.0F);
        world.spawnEntity(entityendercrystal);
    }

    private void addCustomSpawner(World world, int x, int y, int z, String id)
    {
        TileEntityMobSpawner mobspawner = (TileEntityMobSpawner) realizeBlock(world, x, y, z, Blocks.MOB_SPAWNER, UNSPECIFIED_METADATA, RuinsMod.DIR_NORTH);
        if (mobspawner != null)
        {
            ResourceLocation rsl = new ResourceLocation(id);
            Entity test = EntityList.createEntityByIDFromName(rsl, world);
            if (test == null)
            {
                System.err.println("Warning: Ruins Mod could not find an Entity [" + id + "] set for a Mob Spawner");
                for (ResourceLocation entString : EntityList.getEntityNameList())
                {
                    if (entString.getResourcePath().contains(id))
                    {
                        rsl = entString;
                        System.err.println("Ruins Mod going with containing match [" + id + "]");
                        break;
                    }
                }
            }
            mobspawner.getSpawnerBaseLogic().setEntityId(rsl);
        }
    }

    private void addEasyMobSpawn(World world, Random random, int x, int y, int z)
    {
        switch (random.nextInt(2))
        {
        case 0:
            addCustomSpawner(world, x, y, z, "Skeleton");
            break;
        default:
            addCustomSpawner(world, x, y, z, "Zombie");
            break;
        }
    }

    private void addMediumMobSpawn(World world, Random random, int x, int y, int z)
    {
        switch (random.nextInt(4))
        {
        case 0:
            addCustomSpawner(world, x, y, z, "Spider");
            break;
        case 1:
            addCustomSpawner(world, x, y, z, "Skeleton");
            break;
        case 2:
            addCustomSpawner(world, x, y, z, "Cave_Spider");
            break;
        default:
            addCustomSpawner(world, x, y, z, "Zombie");
            break;
        }
    }

    private void addHardMobSpawn(World world, Random random, int x, int y, int z)
    {
        switch (random.nextInt(4))
        {
        case 0:
            addCustomSpawner(world, x, y, z, "Creeper");
            break;
        case 1:
            addCustomSpawner(world, x, y, z, "Cave_Spider");
            break;
        case 2:
            addCustomSpawner(world, x, y, z, "Skeleton");
            break;
        default:
            addCustomSpawner(world, x, y, z, "Blaze");
            break;
        }
    }

    private void addUprightMobSpawn(World world, Random random, int x, int y, int z)
    {
        switch (random.nextInt(3))
        {
        case 0:
            addCustomSpawner(world, x, y, z, "Creeper");
            break;
        case 1:
            addCustomSpawner(world, x, y, z, "Skeleton");
            break;
        default:
            addCustomSpawner(world, x, y, z, "Zombie");
            break;
        }
    }

    private void addEasyChest(World world, Random random, int x, int y, int z, int meta, int direction, int items)
    {
        TileEntityChest chest = (TileEntityChest) realizeBlock(world, x, y, z, Blocks.CHEST, meta, direction);
        if (chest != null)
        {
            ItemStack stack;
            for (int i = 0; i < items; i++)
            {
                stack = getNormalStack(random);
                if (stack != null)
                {
                    chest.setInventorySlotContents(random.nextInt(chest.getSizeInventory()), stack);
                }
            }
        }
    }

    private void addMediumChest(World world, Random random, int x, int y, int z, int meta, int direction, int items)
    {
        TileEntityChest chest = (TileEntityChest) realizeBlock(world, x, y, z, Blocks.CHEST, meta, direction);
        if (chest != null)
        {
            ItemStack stack;
            for (int i = 0; i < items; i++)
            {
                if (random.nextInt(20) < 19)
                {
                    stack = getNormalStack(random);
                }
                else
                {
                    stack = getLootStack(random);
                }
                if (stack != null)
                {
                    chest.setInventorySlotContents(random.nextInt(chest.getSizeInventory()), stack);
                }
            }
        }
    }

    private void addHardChest(World world, Random random, int x, int y, int z, int meta, int direction, int items)
    {
        TileEntityChest chest = (TileEntityChest) realizeBlock(world, x, y, z, Blocks.CHEST, meta, direction);
        if (chest != null)
        {
            ItemStack stack;
            for (int i = 0; i < items; i++)
            {
                if (random.nextInt(10) < 9)
                {
                    stack = getNormalStack(random);
                }
                else
                {
                    stack = getLootStack(random);
                }
                if (stack != null)
                {
                    chest.setInventorySlotContents(random.nextInt(chest.getSizeInventory()), stack);
                }
            }
        }
    }

    private void addChestGenChest(World world, Random random, int x, int y, int z, String gen, int targetCount, int meta, int direction)
    {
        TileEntityChest chest = (TileEntityChest) realizeBlock(world, x, y, z, Blocks.CHEST, meta, direction);
        if (chest != null)
        {
            ResourceLocation lootTable;
            if (gen.contains(":")) {
                String[] pair = gen.split(":");
                lootTable = new ResourceLocation(pair[0], pair[1]);
            } else {
                lootTable = new ResourceLocation("minecraft", gen);
            }

            LootTable loottable = world.getLootTableManager().getLootTableFromLocation(lootTable);

            LootContext.Builder lootContextBuilder = new LootContext.Builder((WorldServer) world);

            List<ItemStack> lootFromPools = loottable.generateLootForPools(random, lootContextBuilder.build());
            int toRemove = lootFromPools.size() - targetCount;
            if (excessiveDebugging)
            {
                debugPrinter.println("addChestGenChest running with gen[" + gen + "], loot pool size " + lootFromPools.size() + ", targetCount " + targetCount);
            }
            if (targetCount > 0 && toRemove > 0)
            {
                Collections.shuffle(lootFromPools);
                Iterator<ItemStack> iter = lootFromPools.iterator();
                while (toRemove > 0)
                {
                    iter.next();
                    iter.remove();
                    toRemove--;
                }
            }
            if (excessiveDebugging)
            {
                debugPrinter.println("addChestGenChest post removal loot pool size " + lootFromPools.size());
            }

            List<Integer> emptySlotsRandomized = getEmptySlotsRandomized(chest, random);
            shuffleItems(lootFromPools, emptySlotsRandomized.size(), random);

            for (ItemStack itemstack : lootFromPools)
            {
                if (emptySlotsRandomized.isEmpty())
                {
                    return;
                }

                if (itemstack == null)
                {
                    chest.setInventorySlotContents(emptySlotsRandomized.remove(emptySlotsRandomized.size() - 1), null);
                }
                else
                {
                    chest.setInventorySlotContents(emptySlotsRandomized.remove(emptySlotsRandomized.size() - 1), itemstack);
                }
            }
        }
    }

    private List<Integer> getEmptySlotsRandomized(IInventory inventory, Random rand)
    {
        List<Integer> list = Lists.<Integer> newArrayList();

        for (int i = 0; i < inventory.getSizeInventory(); ++i)
        {
            if (inventory.getStackInSlot(i) == ItemStack.EMPTY)
            {
                list.add(i);
            }
        }

        Collections.shuffle(list, rand);
        return list;
    }

    private void shuffleItems(List<ItemStack> stacks, int targetSize, Random rand)
    {
        List<ItemStack> list = Lists.<ItemStack> newArrayList();
        Iterator<ItemStack> iterator = stacks.iterator();

        while (iterator.hasNext())
        {
            ItemStack itemstack = iterator.next();

            if (itemstack.getCount() <= 0)
            {
                iterator.remove();
            }
            else if (itemstack.getCount() > 1)
            {
                list.add(itemstack);
                iterator.remove();
            }
        }

        targetSize = targetSize - stacks.size();

        while (targetSize > 0 && list.size() > 0)
        {
            ItemStack itemstack2 = list.remove(MathHelper.getInt(rand, 0, list.size() - 1));
            int i = MathHelper.getInt(rand, 1, itemstack2.getCount() / 2);
            itemstack2.shrink(i);
            ItemStack itemstack1 = itemstack2.copy();
            itemstack1.setCount(i);

            if (itemstack2.getCount() > 1 && rand.nextBoolean())
            {
                list.add(itemstack2);
            }
            else
            {
                stacks.add(itemstack2);
            }

            if (itemstack1.getCount() > 1 && rand.nextBoolean())
            {
                list.add(itemstack1);
            }
            else
            {
                stacks.add(itemstack1);
            }
        }

        stacks.addAll(list);
        Collections.shuffle(stacks, rand);
    }

    @SuppressWarnings("deprecation")
    private void addIInventoryBlock(World world, Random random, int x, int y, int z, Block block, String itemDataWithoutNBT, ArrayList<String> nbtTags, int metadata, int direction)
    {
        TileEntity te = realizeBlock(world, x, y, z, block, metadata, direction);
        if (te instanceof IInventory)
        {
            if (excessiveDebugging)
            {
                debugPrinter.println("About to construct IInventory, itemData [" + itemDataWithoutNBT + "]");
            }

            if (itemDataWithoutNBT.startsWith("ChestGenHook:")) // ChestGenHook:dungeonChest:5
            {
                String[] input = itemDataWithoutNBT.split(":");
                ResourceLocation lootTable = new ResourceLocation("minecraft", input[1]);
                LootTable loottable = world.getLootTableManager().getLootTableFromLocation(lootTable);
                LootContext.Builder lootcontext$builder = new LootContext.Builder((WorldServer) world);
                loottable.fillInventory((IInventory) te, random, lootcontext$builder.build());
            }
            else
            {
                handleIInventory((IInventory) te, itemDataWithoutNBT, nbtTags);
            }
            rotateTileEntity(te, direction);
        }
        else
        {
            System.err.println("Ruins Mod could not find IInventory instance for [" + block + "] in Ruin template: " + owner.getName());
        }
    }

    private void handleIInventory(IInventory inv, String itemDataWithoutNBT, ArrayList<String> nbtTags)
    {
        // example string:
        // minecraft:stone#1#4#0+minecraft:written_book#NBT1#0#1+minecraft:chest#1#0#2
        ItemStack putItem;
        ItemStack slotItemPrev;
        String[] itemStrings = itemDataWithoutNBT.split(Pattern.quote("+"));
        String[] hashsplit;
        Object o;
        if (excessiveDebugging)
        {
            debugPrinter.println("itemStrings length: " + itemStrings.length);
        }
        for (String itemstring : itemStrings)
        {
            if (excessiveDebugging)
            {
                debugPrinter.println("itemString: " + itemstring);
            }
            hashsplit = itemstring.split("#");

            int itemStackSize = 1;
            boolean nbtdata = false;
            if (hashsplit.length > 1)
            {
                nbtdata = hashsplit[1].startsWith("NBT");
                if (!nbtdata)
                {
                    itemStackSize = Integer.valueOf(hashsplit[1]);
                }
            }

            int itemMeta = hashsplit.length > 2 ? Integer.valueOf(hashsplit[2]) : 0;
            int targetslot = hashsplit.length > 3 ? Integer.valueOf(hashsplit[3]) : -1;
            o = tryFindingObject(hashsplit[0]);
            if (excessiveDebugging)
            {
                debugPrinter.println(hashsplit[0] + " resolved to object " + o);
            }

            putItem = null;
            if (o instanceof Block)
            {
                putItem = new ItemStack(((Block) o), itemStackSize, itemMeta);
            }
            else if (o instanceof Item)
            {
                putItem = new ItemStack(((Item) o), itemStackSize, itemMeta);
            }
            if (excessiveDebugging)
            {
                debugPrinter.println("itemstack instance: " + putItem);
            }

            if (putItem != null)
            {
                if (nbtdata)
                {
                    try
                    {
                        hashsplit[1] = restoreNBTTags(hashsplit[1], nbtTags);
                        if (excessiveDebugging)
                        {
                            debugPrinter.println("trying to apply nbt tag: " + hashsplit[1]);
                        }
                        putItem.setTagCompound(JsonToNBT.getTagFromJson(hashsplit[1]));
                    }
                    catch (NBTException e)
                    {
                        e.printStackTrace();
                    }
                }
                if (targetslot != -1)
                {
                    slotItemPrev = inv.getStackInSlot(targetslot);
                    if (slotItemPrev == ItemStack.EMPTY)
                    {
                        inv.setInventorySlotContents(targetslot, putItem);
                    }
                    else if (slotItemPrev.isItemEqual(putItem))
                    {
                        int freeSize = slotItemPrev.getMaxStackSize() - slotItemPrev.getCount();
                        if (freeSize >= putItem.getCount())
                        {
                            slotItemPrev.grow(putItem.getCount());
                        }
                        else
                        {
                            slotItemPrev.grow(freeSize);
                        }
                    }
                }
                else
                {
                    for (int slot = 0; slot < inv.getSizeInventory(); slot++)
                    {
                        slotItemPrev = inv.getStackInSlot(slot);
                        if (slotItemPrev == ItemStack.EMPTY)
                        {
                            inv.setInventorySlotContents(slot, putItem);
                            break;
                        }
                        else if (slotItemPrev.isItemEqual(putItem))
                        {
                            int freeSize = slotItemPrev.getMaxStackSize() - slotItemPrev.getCount();
                            if (freeSize >= putItem.getCount())
                            {
                                slotItemPrev.grow(putItem.getCount());
                                break;
                            }
                            else
                            {
                                slotItemPrev.grow(freeSize);
                                putItem.shrink(freeSize);
                            }
                        }
                    }
                }
            }
        }
    }

    private Object tryFindingObject(String s)
    {
        ResourceLocation rl = new ResourceLocation(s);
        Item item = Item.REGISTRY.getObject(rl);
        if (item != null)
        {
            if (item instanceof ItemBlock)
            {
                return ((ItemBlock) item).getBlock();
            }
            return item;
        }

        Block block = Block.REGISTRY.getObject(rl);
        if (block != Blocks.AIR)
        {
            return block;
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private void addCommandBlock(World world, int x, int y, int z, String command, String sender, int meta, int rotate)
    {
        TileEntityCommandBlock tecb = (TileEntityCommandBlock) realizeBlock(world, x, y, z, Blocks.COMMAND_BLOCK, meta, rotate);
        if (tecb != null)
        {
            tecb.getCommandBlockLogic().setCommand(findAndRotateRelativeCommandBlockCoords(command, rotate));
            tecb.getCommandBlockLogic().setName(sender);
        }
    }

    private String findAndRotateRelativeCommandBlockCoords(String command, int rotate)
    {
        if (rotate != RuinsMod.DIR_NORTH)
        {
            if (excessiveDebugging)
            {
                debugPrinter.println("About to parse command block command [" + command + "] for relative coordinates and try to rotate them");
            }
            /*
             * regex pattern to find each coordinate triple with at least x and
             * z relative (with tilde), save xz numbers and y in groups
             */
            final Pattern coordinates = Pattern.compile("~(-\\d+|\\d*)[ \\t]+(~?-?\\d+|~)[ \\t]+~(-\\d+|\\d*)");
            final Matcher coordinateMatcher = coordinates.matcher(command);
            final StringBuffer stringBuffer = new StringBuffer();
            /* for each pattern match do */
            while (coordinateMatcher.find())
            {
                if (excessiveDebugging)
                {
                    debugPrinter.println("Found contained coordinate triple: " + coordinateMatcher.group());
                }
                if (rotate == RuinsMod.DIR_EAST)
                {
                    // z multiplied with -1 becomes x, x becomes z
                    coordinateMatcher.appendReplacement(stringBuffer, String.format("~%s %s ~%s", (tryToInvert(coordinateMatcher.group(3))), coordinateMatcher.group(2), coordinateMatcher.group(1)));
                }
                else if (rotate == RuinsMod.DIR_WEST)
                {
                    // x multiplied with -1 becomes z, z becomes x
                    coordinateMatcher.appendReplacement(stringBuffer, String.format("~%s %s ~%s", coordinateMatcher.group(3), coordinateMatcher.group(2), tryToInvert(coordinateMatcher.group(1))));
                }
                else
                {
                    // case DIR_SOUTH, just negate x and z numbers
                    coordinateMatcher.appendReplacement(stringBuffer, String.format("~%s %s ~%s", tryToInvert(coordinateMatcher.group(1)), coordinateMatcher.group(2), tryToInvert(coordinateMatcher.group(3))));
                }
            }
            /*
             * rebuild the pattern with the changes. we have the technology. we
             * can make it better
             */
            coordinateMatcher.appendTail(stringBuffer);
            final String result = stringBuffer.toString();
            if (excessiveDebugging)
            {
                debugPrinter.println("Command Block command with rotated coords: [" + result + "]");
            }
            return result;
        }
        return command;
    }

    /**
     * Possible inputs are "", "x", "-x" with int x
     *
     * @param maybeNumber
     *            input string
     * @return "-input" or "" if not applicable
     */
    private String tryToInvert(String maybeNumber)
    {
        if (!maybeNumber.isEmpty())
        {
            return Integer.toString(Integer.parseInt(maybeNumber) * -1);
        }
        return "";
    }

    private ItemStack getNormalStack(Random random)
    {
        int rand = random.nextInt(25);
        switch (rand)
        {
        case 0:
        case 1:
            return null;
        case 2:
        case 3:
            return new ItemStack(Items.BREAD);
        case 4:
        case 5:
            return new ItemStack(Items.WHEAT, random.nextInt(8) + 8);
        case 6:
            return new ItemStack(Items.IRON_HOE);
        case 7:
            return new ItemStack(Items.IRON_SHOVEL);
        case 8:
        case 9:
            return new ItemStack(Items.STRING, random.nextInt(3) + 1);
        case 10:
        case 11:
        case 12:
            return new ItemStack(Items.WHEAT_SEEDS, random.nextInt(8) + 8);
        case 13:
        case 14:
        case 15:
            return new ItemStack(Items.BOWL, random.nextInt(2) + 1);
        case 16:
            return new ItemStack(Items.BUCKET);
        case 17:
            return new ItemStack(Items.APPLE);
        case 18:
        case 19:
            return new ItemStack(Items.BONE, random.nextInt(4) + 1);
        case 20:
        case 21:
            return new ItemStack(Items.EGG, random.nextInt(2) + 1);
        case 22:
            return new ItemStack(Items.COAL, random.nextInt(5) + 3);
        case 23:
            return new ItemStack(Items.IRON_INGOT, random.nextInt(5) + 3);
        default:
            return getLootStack(random);
        }
    }

    private ItemStack getLootStack(Random random)
    {
        int rand = random.nextInt(25);
        switch (rand)
        {
        case 0:
        case 1:
        case 2:
        case 3:
            return null;
        case 4:
        case 5:
            return new ItemStack(Items.LEATHER_BOOTS);
        case 6:
        case 7:
            return new ItemStack(Items.LEATHER_LEGGINGS);
        case 8:
        case 9:
            return new ItemStack(Items.FLINT_AND_STEEL);
        case 10:
        case 11:
            return new ItemStack(Items.IRON_AXE);
        case 12:
            return new ItemStack(Items.IRON_SWORD);
        case 13:
            return new ItemStack(Items.IRON_PICKAXE);
        case 14:
        case 15:
            return new ItemStack(Items.IRON_HELMET);
        case 16:
            return new ItemStack(Items.IRON_CHESTPLATE);
        case 17:
        case 18:
            return new ItemStack(Items.BOOK, random.nextInt(3) + 1);
        case 19:
            return new ItemStack(Items.COMPASS);
        case 20:
            return new ItemStack(Items.CLOCK);
        case 21:
            return new ItemStack(Items.REDSTONE, random.nextInt(12) + 12);
        case 22:
            return new ItemStack(Items.GOLDEN_APPLE);
        case 23:
            return new ItemStack(Items.MUSHROOM_STEW, random.nextInt(2) + 1);
        default:
            return new ItemStack(Items.DIAMOND, random.nextInt(4));
        }
    }

    private boolean isAir(String block)
    {
        return block.equals("air") || block.equals("minecraft:air");
    }

    // is given metadata value valid?
    private static boolean isValidMetadata(int metadata)
    {
        return metadata >= 0 && metadata < 16;
    }

    // invalid sentinel metadata value indicating none was specified and default should be used
    private static final int UNSPECIFIED_METADATA = -1;

    // get rotation (minecraft enum) corresponding to given direction (ruins int)
    private static Rotation getDirectionalRotation(int direction)
    {
        Rotation rotation = Rotation.NONE;
        switch (direction)
        {
        case RuinsMod.DIR_EAST:
            rotation = Rotation.CLOCKWISE_90;
            break;
        case RuinsMod.DIR_SOUTH:
            rotation = Rotation.CLOCKWISE_180;
            break;
        case RuinsMod.DIR_WEST:
            rotation = Rotation.COUNTERCLOCKWISE_90;
            break;
        }
        return rotation;
    }

    // make specified block manifest in world, with given metadata and direction
    // returns associated tile entity, if there is one
    @SuppressWarnings("deprecation")
    private static TileEntity realizeBlock(World world, int x, int y, int z, Block block, int metadata, int direction)
    {
        TileEntity entity = null;
        if (world != null && block != null)
        {
            BlockPos position = new BlockPos(x, y, z);

            // clobber existing tile entity block, if any
            TileEntity existing_entity = world.getTileEntity(position);
            if (existing_entity != null)
            {
                if (existing_entity instanceof IInventory)
                {
                    ((IInventory) existing_entity).clear();
                }
                world.setBlockState(position, Blocks.BARRIER.getDefaultState(), 4);
            }

            final IBlockState state = (isValidMetadata(metadata) ? block.getStateFromMeta(metadata) : block.getDefaultState()).withRotation(getDirectionalRotation(direction));
            if (world.setBlockState(position, state, 2))
            {
                // workaround for vanilla weirdness (bug?)
                // double set required for some states (e.g., rails)
                world.setBlockState(position, state, 2);
                entity = world.getTileEntity(position);
            }
        }
        return entity;
    }

    // apply given direction to specified tile entity
    private static void rotateTileEntity(TileEntity entity, int direction)
    {
        if (entity != null)
        {
            entity.rotate(getDirectionalRotation(direction));
            entity.markDirty();
        }
    }
}
