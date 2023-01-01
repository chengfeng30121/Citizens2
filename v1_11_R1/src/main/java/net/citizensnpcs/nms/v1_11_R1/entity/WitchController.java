package net.citizensnpcs.nms.v1_11_R1.entity;

import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_11_R1.CraftServer;
import org.bukkit.craftbukkit.v1_11_R1.entity.CraftEntity;
import org.bukkit.craftbukkit.v1_11_R1.entity.CraftWitch;
import org.bukkit.entity.Witch;
import org.bukkit.util.Vector;

import net.citizensnpcs.api.event.NPCEnderTeleportEvent;
import net.citizensnpcs.api.event.NPCKnockbackEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.nms.v1_11_R1.util.NMSImpl;
import net.citizensnpcs.npc.CitizensNPC;
import net.citizensnpcs.npc.ai.NPCHolder;
import net.citizensnpcs.util.Util;
import net.minecraft.server.v1_11_R1.BlockPosition;
import net.minecraft.server.v1_11_R1.Entity;
import net.minecraft.server.v1_11_R1.EntityWitch;
import net.minecraft.server.v1_11_R1.IBlockData;
import net.minecraft.server.v1_11_R1.NBTTagCompound;
import net.minecraft.server.v1_11_R1.SoundEffect;
import net.minecraft.server.v1_11_R1.World;

public class WitchController extends MobEntityController {
    public WitchController() {
        super(EntityWitchNPC.class);
    }

    @Override
    public Witch getBukkitEntity() {
        return (Witch) super.getBukkitEntity();
    }

    public static class EntityWitchNPC extends EntityWitch implements NPCHolder {
        private final CitizensNPC npc;

        public EntityWitchNPC(World world) {
            this(world, null);
        }

        public EntityWitchNPC(World world, NPC npc) {
            super(world);
            this.npc = (CitizensNPC) npc;
        }

        @Override
        protected void a(double d0, boolean flag, IBlockData block, BlockPosition blockposition) {
            if (npc == null || !npc.isFlyable()) {
                super.a(d0, flag, block, blockposition);
            }
        }

        @Override
        public void a(Entity entity, float strength, double dx, double dz) {
            NPCKnockbackEvent event = new NPCKnockbackEvent(npc, strength, dx, dz);
            Bukkit.getPluginManager().callEvent(event);
            Vector kb = event.getKnockbackVector();
            if (!event.isCancelled()) {
                super.a(entity, (float) event.getStrength(), kb.getX(), kb.getZ());
            }
        }

        @Override
        protected SoundEffect bW() {
            return NMSImpl.getSoundEffect(npc, super.bW(), NPC.Metadata.DEATH_SOUND);
        }

        @Override
        protected SoundEffect bX() {
            return NMSImpl.getSoundEffect(npc, super.bX(), NPC.Metadata.HURT_SOUND);
        }

        @Override
        public void collide(net.minecraft.server.v1_11_R1.Entity entity) {
            // this method is called by both the entities involved - cancelling
            // it will not stop the NPC from moving.
            super.collide(entity);
            if (npc != null)
                Util.callCollisionEvent(npc, entity.getBukkitEntity());
        }

        @Override
        public boolean d(NBTTagCompound save) {
            return npc == null ? super.d(save) : false;
        }

        @Override
        public void e(float f, float f1) {
            if (npc == null || !npc.isFlyable()) {
                super.e(f, f1);
            }
        }

        @Override
        public void enderTeleportTo(double d0, double d1, double d2) {
            if (npc == null)
                super.enderTeleportTo(d0, d1, d2);
            NPCEnderTeleportEvent event = new NPCEnderTeleportEvent(npc);
            Bukkit.getPluginManager().callEvent(event);
            if (!event.isCancelled()) {
                super.enderTeleportTo(d0, d1, d2);
            }
        }

        @Override
        public void f(double x, double y, double z) {
            Vector vector = Util.callPushEvent(npc, x, y, z);
            if (vector != null) {
                super.f(vector.getX(), vector.getY(), vector.getZ());
            }
        }

        @Override
        public void g(float f, float f1) {
            if (npc == null || !npc.isFlyable()) {
                super.g(f, f1);
            } else {
                NMSImpl.flyingMoveLogic(this, f, f1);
            }
        }

        @Override
        protected SoundEffect G() {
            return NMSImpl.getSoundEffect(npc, super.G(), NPC.Metadata.AMBIENT_SOUND);
        }

        @Override
        public CraftEntity getBukkitEntity() {
            if (npc != null && !(bukkitEntity instanceof NPCHolder))
                bukkitEntity = new WitchNPC(this);
            return super.getBukkitEntity();
        }

        @Override
        public NPC getNPC() {
            return npc;
        }

        @Override
        public boolean isLeashed() {
            if (npc == null)
                return super.isLeashed();
            boolean protectedDefault = npc.isProtected();
            if (!protectedDefault || !npc.data().get(NPC.Metadata.LEASH_PROTECTED, protectedDefault))
                return super.isLeashed();
            if (super.isLeashed()) {
                unleash(true, false); // clearLeash with client update
            }
            return false; // shouldLeash
        }

        @Override
        protected void L() {
            if (npc == null) {
                super.L();
            }
        }

        @Override
        public void M() {
            super.M();
            if (npc != null)
                npc.update();
        }

        @Override
        public boolean m_() {
            if (npc == null || !npc.isFlyable()) {
                return super.m_();
            } else {
                return false;
            }
        }

        @Override
        public void setSize(float f, float f1) {
            if (npc == null) {
                super.setSize(f, f1);
            } else {
                NMSImpl.setSize(this, f, f1, justCreated);
            }
        }
    }

    public static class WitchNPC extends CraftWitch implements NPCHolder {
        private final CitizensNPC npc;

        public WitchNPC(EntityWitchNPC entity) {
            super((CraftServer) Bukkit.getServer(), entity);
            this.npc = entity.npc;
        }

        @Override
        public NPC getNPC() {
            return npc;
        }
    }
}
