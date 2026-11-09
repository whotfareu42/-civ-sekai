package com.untamedears.realisticbiomes.growth;

import com.untamedears.realisticbiomes.model.Plant;
import java.util.Random;
import net.minecraft.util.RandomSource;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.util.RandomSourceWrapper;

/**
 * We need to differentiate fungus from other types of saplings thanks
 * to the peculiarities with how Spigot handles tree generation. If you
 * call {@code world.generateTree()} with the {@link TreeType}, it will
 * generate the tree as if it were populating a newly generated chunk.
 * Therefore we need to bypass that and pretend the sapling is being
 * bone mealed so ensure a singular tree is generated.
 */
public class FungusGrower extends AgeableGrower {

    private final RandomSource randomSource = new RandomSourceWrapper(new Random());

    public FungusGrower(final Material material) {
        super(material, 1, 1);
    }

    @Override
    public int getStage(final Plant plant) {
        final Block block = plant.getLocation().getBlock();
        if (block.getType() != this.material) {
            return -1;
        }
        return 0;
    }

    @Override
    public boolean setStage(final Plant plant, final int stage) {
        if (stage < 1) {
            return true;
        }
        final Block block = plant.getLocation().getBlock();
        final Material material = block.getType();
        if (material != Material.CRIMSON_FUNGUS && material != Material.WARPED_FUNGUS
            && material != Material.FLOWERING_AZALEA) {
            return true;
        }
        // Paper 26.x: grow it the same way bone meal does (single tree, not chunk population)
        for (int attempt = 0; attempt < 32 && block.getType() == material; attempt++) {
            block.applyBoneMeal(org.bukkit.block.BlockFace.UP);
        }
        return true;
    }

    @Override
    public boolean deleteOnFullGrowth() {
        return true;
    }

}
