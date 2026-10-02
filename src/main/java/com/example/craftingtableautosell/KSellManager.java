package com.example.craftingtableautosell;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
public final class KSellManager {
    private static final int INTERVAL_TICKS = 30;
    private boolean enabled; private int price; private int waitTicks; private int lockedHotbarSlot=-1; private ItemStack target=ItemStack.EMPTY;
    public void startFromHand(int price,Minecraft client){if(client.player==null)return; ItemStack hand=client.player.getMainHandItem(); if(hand.isEmpty()){msg(client,"KSell: hold an item in your main hand.",ChatFormatting.RED);return;} this.price=price;target=hand.copyWithCount(1);lockedHotbarSlot=client.player.getInventory().getSelectedSlot();waitTicks=0;enabled=true;msg(client,"KSell: ON, selling 1x "+hand.getHoverName().getString()+" every 1.5s.",ChatFormatting.GREEN);}
    public void stop(Minecraft client){enabled=false;waitTicks=0;lockedHotbarSlot=-1;target=ItemStack.EMPTY;msg(client,"KSell: OFF",ChatFormatting.RED);}
    public void tick(Minecraft client){if(!enabled||client.player==null||client.player.connection==null)return;if(client.gui.screen()!=null)return;LocalPlayer p=client.player;InventoryMenu m=p.inventoryMenu;Inventory inv=p.getInventory();if(!m.getCarried().isEmpty())return;if(lockedHotbarSlot<0||inv.getSelectedSlot()!=lockedHotbarSlot)return;if(waitTicks>0){waitTicks--;return;}int hs=36+lockedHotbarSlot;ItemStack hand=inv.getItem(lockedHotbarSlot);if(isTarget(hand)&&hand.getCount()==1){sell(client);return;}int source=findTargetSlot(inv);if(source<0){stopNoItems(client);return;}int ss=inventoryIndexToScreenSlot(source);ItemStack st=m.getSlot(ss).getItem();if(ss==hs){if(st.getCount()!=1&&!splitHand(client,m,hs))return;}else if(st.getCount()==1)swap(client,ss,lockedHotbarSlot);else if(!splitAndSwap(client,m,ss,lockedHotbarSlot))return;hand=inv.getItem(lockedHotbarSlot);if(isTarget(hand)&&hand.getCount()==1)sell(client);}
    private void sell(Minecraft c){c.player.connection.sendCommand("ah sell "+price);waitTicks=INTERVAL_TICKS;}
    private int findTargetSlot(Inventory i){for(int n=0;n<36;n++)if(isTarget(i.getItem(n)))return n;return -1;}
    private boolean isTarget(ItemStack s){return !s.isEmpty()&&ItemStack.isSameItemSameComponents(s,target);}
    private boolean splitHand(Minecraft c,InventoryMenu m,int slot){int scratch=findEmptySlot(m,slot,-1);if(scratch<0)return false;click(c,slot,0,ContainerInput.PICKUP);if(m.getCarried().isEmpty())return false;click(c,slot,1,ContainerInput.PICKUP);if(m.getSlot(slot).getItem().getCount()!=1)return false;click(c,scratch,0,ContainerInput.PICKUP);return m.getCarried().isEmpty();}
    private boolean splitAndSwap(Minecraft c,InventoryMenu m,int source,int hotbar){int scratch=findEmptySlot(m,source,36+hotbar);if(scratch<0)return false;click(c,source,0,ContainerInput.PICKUP);if(m.getCarried().isEmpty())return false;click(c,scratch,1,ContainerInput.PICKUP);if(m.getSlot(scratch).getItem().getCount()!=1)return false;click(c,source,0,ContainerInput.PICKUP);if(!m.getCarried().isEmpty())return false;click(c,scratch,hotbar,ContainerInput.SWAP);return true;}
    private void swap(Minecraft c,int source,int hotbar){click(c,source,hotbar,ContainerInput.SWAP);}
    private void click(Minecraft c,int slot,int button,ContainerInput action){c.gameMode.handleContainerInput(c.player.inventoryMenu.containerId,slot,button,action,c.player);}
    private int findEmptySlot(InventoryMenu m,int e1,int e2){for(int s=9;s<=35;s++)if(s!=e1&&s!=e2&&m.getSlot(s).getItem().isEmpty())return s;for(int s=36;s<=44;s++)if(s!=e1&&s!=e2&&m.getSlot(s).getItem().isEmpty())return s;return -1;}
    private int inventoryIndexToScreenSlot(int i){return i<9?36+i:i;}
    private void stopNoItems(Minecraft c){enabled=false;msg(c,"KSell: OFF, no matching items left.",ChatFormatting.YELLOW);}
    private void msg(Minecraft c,String t,ChatFormatting color){if(c.player!=null)c.player.sendSystemMessage(Component.literal(t).withStyle(color));}
}