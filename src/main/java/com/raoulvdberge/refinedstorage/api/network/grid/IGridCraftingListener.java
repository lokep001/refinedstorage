package com.raoulvdberge.refinedstorage.api.network.grid;

/**
 * A crafting listener for the grid.
 */
public interface IGridCraftingListener {
    /**
     * Called when the crafting output changes.
     * Usually you'd send slot updates for the crafting slots (and output slot) here, so that all clients get an actual view.
     */
    void onCraftingOutputChanged();
}
