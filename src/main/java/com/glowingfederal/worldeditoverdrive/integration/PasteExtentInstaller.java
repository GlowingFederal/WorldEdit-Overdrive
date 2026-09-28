package com.glowingfederal.worldeditoverdrive.integration;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.ChangeSetExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extent.reorder.MultiStageReorder;
import com.sk89q.worldedit.history.changeset.BlockOptimizedHistory;
import java.lang.reflect.Field;

/** Compatibility boundary for the pinned Enhanced 6.3.0 extent chain. */
final class PasteExtentInstaller {
    static String incompatibility(EditSession session) {
        try {
            if (session.getChangeSet().getClass() != BlockOptimizedHistory.class || session.getChangeSet().size() != 0) return "expected fresh native history";
            Object reorder=get(session,"reorderExtent");if(reorder.getClass()!=MultiStageReorder.class)return "custom reorder extent";
            for(String name:new String[]{"stage1","stage2","stage3"})if(!((java.util.List<?>)field(MultiStageReorder.class,name).get(reorder)).isEmpty())return "reorder queue already contains work";
            if (!(get(session, "changeSetExtent") instanceof ChangeSetExtent)) return "missing native history extent";
            // Verify exact fields before command ownership. No file or page allocation here.
            field(EditSession.class, "changeSet"); field(ChangeSetExtent.class, "changeSet"); field(AbstractDelegateExtent.class, "extent");
            return null;
        } catch (Exception e) { return "Enhanced extent fields unavailable: " + e; }
    }
    static PasteStreamingExtent install(EditSession session, PasteStreamStorage storage, boolean history) throws Exception {
        MultiStageReorder old = (MultiStageReorder) get(session, "reorderExtent");
        PasteStreamingExtent replacement = new PasteStreamingExtent(old.getExtent(), old.isEnabled(), storage);
        replacement.original=old;
        Extent cursor = (Extent) get(session, "bypassNone"); AbstractDelegateExtent parent = null;
        while (cursor != old && cursor instanceof AbstractDelegateExtent) { parent = (AbstractDelegateExtent) cursor; cursor = parent.getExtent(); }
        if (cursor != old || parent == null) throw new IllegalStateException("reorder chain changed after admission");
        field(AbstractDelegateExtent.class, "extent").set(parent, replacement);
        field(EditSession.class, "reorderExtent").set(session, replacement);
        if (get(session, "bypassHistory") == old) field(EditSession.class, "bypassHistory").set(session, replacement);
        if (history) {
            PasteDiskHistory changes = new PasteDiskHistory(storage);
            field(EditSession.class, "changeSet").set(session, changes);
            field(ChangeSetExtent.class, "changeSet").set(get(session, "changeSetExtent"), changes);
        }
        return replacement;
    }
    static void restore(EditSession session,PasteStreamingExtent replacement)throws Exception {
        if(get(session,"reorderExtent")!=replacement)return;
        Extent cursor=(Extent)get(session,"bypassNone");AbstractDelegateExtent parent=null;
        while(cursor!=replacement&&cursor instanceof AbstractDelegateExtent){parent=(AbstractDelegateExtent)cursor;cursor=parent.getExtent();}
        if(parent==null||cursor!=replacement)throw new IllegalStateException("streaming reorder chain changed");
        field(AbstractDelegateExtent.class,"extent").set(parent,replacement.original);
        field(EditSession.class,"reorderExtent").set(session,replacement.original);
        if(get(session,"bypassHistory")==replacement)field(EditSession.class,"bypassHistory").set(session,replacement.original);
    }
    static Extent bypassHistory(EditSession session) throws Exception { return (Extent) get(session, "bypassHistory"); }
    static Object get(EditSession session, String name) throws Exception { return field(EditSession.class, name).get(session); }
    private static Field field(Class<?> type, String name) throws Exception { Field f = type.getDeclaredField(name); f.setAccessible(true); return f; }
}
