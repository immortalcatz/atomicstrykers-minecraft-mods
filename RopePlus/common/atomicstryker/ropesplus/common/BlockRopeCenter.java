package atomicstryker.ropesplus.common;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IconRegister;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;


public class BlockRopeCenter extends Block
{
    public BlockRopeCenter(int blockIndex)
    {
        super(blockIndex, Material.vine);
        float f = 0.1F;
        setBlockBounds(0.5F - f, 0.0F, 0.5F - f, 0.5F + f, 1.0F, 0.5F + f);
        setTextureFile("/atomicstryker/ropesplus/client/ropesPlusBlocks.png");
    }
    
    @Override
    public void func_94332_a(IconRegister par1IconRegister)
    {
        this.field_94336_cN = par1IconRegister.func_94245_a("ropesplus:rope");
    }
    
    @Override
    public boolean isLadder(World world, int x, int y, int z) 
    {
        return true;
    }

    @Override
    public void onBlockAdded(World world, int i, int j, int k)
    {
        byte xoffset = 0;
        byte zoffset = 0;
        byte ropeending = 0;
        if(world.getBlockId(i - 1, j, k + 0) == this.blockID)
        {
            xoffset = -1;
            zoffset = 0;
        }
        else if(world.getBlockId(i + 1, j, k + 0) == this.blockID)
        {
            xoffset = 1;
            zoffset = 0;
        }
        else if(world.getBlockId(i, j, k - 1) == this.blockID)
        {
            xoffset = 0;
            zoffset = -1;
        }
        else if(world.getBlockId(i, j, k + 1) == this.blockID)
        {
            xoffset = 0;
            zoffset = 1;
        }
        if(xoffset != 0 || zoffset != 0)
        {
            for(int length = 1; length <= 32; length++)
            {
                if((ropeending == 0) & world.isBlockOpaqueCube(i + xoffset, j - length, k + zoffset))
                {
                    ropeending = 2;
                }
                if((ropeending == 0) & (world.getBlockId(i + xoffset, j - length, k + zoffset) == 0))
                {
                    ropeending = 1;
                    world.setBlockAndMetadataWithNotify(i, j, k, 0, 0, 3);
                    world.setBlockAndMetadataWithNotify(i + xoffset, j - length, k + zoffset, this.blockID, 0, 3);
                }
            }

        }
        if((ropeending == 0 || ropeending == 2) & (world.getBlockId(i, j + 1, k) != this.blockID) && !world.isBlockOpaqueCube(i, j + 1, k))
        {
            dropBlockAsItem(world, i, j, k, world.getBlockMetadata(i, j, k), 0);
            world.setBlockAndMetadataWithNotify(i, j, k, 0, 0, 3);
        }
    }

    @Override
    public void onNeighborBlockChange(World world, int i, int j, int k, int l)
    {
        super.onNeighborBlockChange(world, i, j, k, l);
        boolean blockstays = false;
        if(world.getBlockId(i - 1, j, k + 0) == this.blockID)
        {
            blockstays = true;
        }
        if(world.getBlockId(i + 1, j, k + 0) == this.blockID)
        {
            blockstays = true;
        }
        if(world.getBlockId(i, j, k - 1) == this.blockID)
        {
            blockstays = true;
        }
        if(world.getBlockId(i, j, k + 1) == this.blockID)
        {
            blockstays = true;
        }
        if(world.isBlockOpaqueCube(i, j + 1, k))
        {
            blockstays = true;
        }
        if(world.getBlockId(i, j + 1, k) == this.blockID)
        {
            blockstays = true;
        }
        if(!blockstays)
        {
            dropBlockAsItem(world, i, j, k, world.getBlockMetadata(i, j, k), 0);
            world.setBlockAndMetadataWithNotify(i, j, k, 0, 0, 3);
        }
    }

    @Override
    public boolean canPlaceBlockAt(World world, int i, int j, int k)
    {
        if(world.getBlockId(i - 1, j, k + 0) == this.blockID)
        {
            return true;
        }
        if(world.getBlockId(i + 1, j, k + 0) == this.blockID)
        {
            return true;
        }
        if(world.getBlockId(i, j, k - 1) == this.blockID)
        {
            return true;
        }
        if(world.getBlockId(i, j, k + 1) == this.blockID)
        {
            return true;
        }
        if(world.isBlockOpaqueCube(i, j + 1, k))
        {
            return true;
        }
        return world.getBlockId(i, j + 1, k) == this.blockID;
    }
	
    @Override
    public void onBlockDestroyedByPlayer(World world, int a, int b, int c, int l)
    {
        System.out.println("Player destroyed Rope block at "+a+","+b+","+c);	
    	
		int[] coords = RopesPlusCore.areCoordsArrowRope(a, b, c);
		if (coords == null)
		{
			System.out.println("Player destroyed Rope is not Arrow Rope, going on");
			return;
		}
		
		int rope_max_y;
		int rope_min_y;
		
		if (world.getBlockId(a, b, c) == this.blockID)
		{
			world.setBlockAndMetadataWithNotify(a, b, c, 0, 0, 3);
		}
		
		for(int x = 1;; x++)
		{
			if (world.getBlockId(a, b+x, c) != this.blockID)
			{
				rope_max_y = b+x-1;
				System.out.println("Player destroyed Rope goes ["+(x-1)+"] blocks higher, up to "+a+","+rope_max_y+","+c);
				System.out.println("Differing BlockID is: "+world.getBlockId(a, b+x, c));
				break;
			}
		}
		
		for(int x = 0;; x--)
		{
			if (world.getBlockId(a, b+x, c) != this.blockID)
			{
				rope_min_y = b+x+1;
				System.out.println("Player destroyed Rope goes ["+(x+1)+"] blocks lower, down to "+a+","+rope_min_y+","+c);
				break;
			}
		}
		
		int ropelenght = rope_max_y-rope_min_y;
		
		for(int x = 0; x <= ropelenght; x++)
		{
			coords = RopesPlusCore.areCoordsArrowRope(a, rope_min_y+x, c);
			
			world.setBlockAndMetadataWithNotify(a, rope_min_y+x, c, 0, 0, 3);
			
			if (coords != null)
			{
				RopesPlusCore.removeCoordsFromRopeArray(coords);
			}
		}
		
		System.out.println("Player destroyed Rope lenght: "+(rope_max_y-rope_min_y));
		
		if(!world.isRemote)
        {
			EntityItem entityitem = new EntityItem(world, a, b, c, new ItemStack(Item.stick));
			entityitem.delayBeforeCanPickup = 5;
			world.spawnEntityInWorld(entityitem);
			
			entityitem = new EntityItem(world, a, b, c, new ItemStack(Item.feather));
			entityitem.delayBeforeCanPickup = 5;
			world.spawnEntityInWorld(entityitem);
		}
		
		System.out.println("Rope destruction func finished");
	}

    @Override
    public boolean isOpaqueCube()
    {
        return false;
    }

    @Override
    public boolean renderAsNormalBlock()
    {
        return false;
    }

    @Override
    public int getRenderType()
    {
        return 1;
    }
}
