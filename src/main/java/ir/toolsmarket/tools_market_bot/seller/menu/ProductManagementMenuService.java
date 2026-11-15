package ir.toolsmarket.tools_market_bot.seller.menu;

import ir.toolsmarket.tools_market_bot.bot.BotCallback.Action;
import ir.toolsmarket.tools_market_bot.bot.BotButton;
import ir.toolsmarket.tools_market_bot.bot.BotCallback;
import ir.toolsmarket.tools_market_bot.integration.bale.BaleBotClient;
import ir.toolsmarket.tools_market_bot.model.*;
import ir.toolsmarket.tools_market_bot.service.*;
import ir.toolsmarket.tools_market_bot.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static ir.toolsmarket.tools_market_bot.util.OrderMessages.button;

/** An unfinished form is temporary; only the final service call changes persisted data. */
@Service
public class ProductManagementMenuService {
    private enum Step { NAME, BRAND, DESCRIPTION, CATEGORY, STOCK }
    private static final class Draft {
        Integer id;
        String name, brand, description, category, imagePath = "";
        Step step = Step.NAME;
    }
    private final Map<String, Draft> drafts = new ConcurrentHashMap<>();
    private final BaleBotClient client;
    private final ProductCatalogService catalog;
    private final UserAccessService access;
    public ProductManagementMenuService(@Qualifier("sellerBaleBotClient") BaleBotClient client,
                                        ProductCatalogService catalog, UserAccessService access) {
        this.client = client; this.catalog = catalog; this.access = access;
    }
    public void reset(String actor) { drafts.remove(actor); }
    public void showProducts(String actor) {
        access.requirePriceManager(actor);
        reset(actor);
        OrderMessages.send(client, actor, BotButton.MANAGE_PRODUCTS.text(), List.of(List.of(button(BotButton.ADD_PRODUCT.text(), Action.CATALOG_ADD.callback()))));
        List<Product> products = catalog.findAll();
        for (int from = 0; from < products.size(); from += 15) {
            List<List<Map<String, Object>>> buttons = new ArrayList<>();
            for (Product product : products.subList(from, Math.min(from + 15, products.size())))
                buttons.add(List.of(button(BotButton.EDIT_PRODUCT_PREFIX.text() + product.name(), Action.CATALOG_EDIT.callback(product.id())),
                        button(BotButton.DELETE_PRODUCT_PREFIX.text() + product.name(), Action.CATALOG_DELETE.callback(product.id()))));
            OrderMessages.send(client, actor, "برای ویرایش یا حذف، محصول را انتخاب کنید:", buttons);
        }
    }
    public boolean handleCallback(String actor, BotCallback callback) {
        switch (callback.action()) {
            case CATALOG_LIST -> showProducts(actor);
            case CATALOG_ADD -> { access.requirePriceManager(actor); drafts.put(actor, new Draft()); prompt(actor); }
            case CATALOG_EDIT, CATALOG_DELETE, CATALOG_DELETE_CONFIRM -> {
                access.requirePriceManager(actor);
                int id = callback.productId();
                Product product = catalog.findById(id).orElseThrow(() -> new IllegalArgumentException("محصول پیدا نشد."));
                switch (callback.action()) {
                    case CATALOG_EDIT -> {
                        Draft draft = new Draft(); draft.id = id; draft.name = product.name(); draft.brand = product.brand();
                        draft.description = product.description(); draft.category = product.categoryCode(); draft.imagePath = product.imagePath();
                        drafts.put(actor, draft); prompt(actor);
                    }
                    case CATALOG_DELETE -> OrderMessages.send(client, actor, "محصول «" + product.name() + "» از فروش حذف شود؟ سفارش‌های قبلی حفظ می‌شوند.",
                            List.of(List.of(button(BotButton.CONFIRM_DELETE.text(), Action.CATALOG_DELETE_CONFIRM.callback(id)), button(BotButton.BACK.text(), Action.CATALOG_LIST.callback()))));
                    case CATALOG_DELETE_CONFIRM -> { catalog.delete(actor, id); showProducts(actor); }
                    default -> { }
                }
            }
            default -> { return false; }
        }
        return true;
    }
    public boolean handleMessage(String actor, String text) {
        BotButton option = BotButton.fromText(text);
        if (option == BotButton.START) { reset(actor); return false; }
        if (option == BotButton.MANAGE_PRODUCTS) { showProducts(actor); return true; }
        Draft draft = drafts.get(actor);
        if (draft == null) return false;
        access.requirePriceManager(actor);
        if (option == BotButton.CANCEL) { reset(actor); client.sendMessage(actor, "عملیات مدیریت محصول لغو شد."); return true; }
        boolean keep = draft.id != null && text.equals("-");
        switch (draft.step) {
            case NAME -> {
                if (!keep) draft.name = required(text);
                draft.step = Step.BRAND;
            }
            case BRAND -> {
                if (!keep) draft.brand = required(text);
                // Legacy products may have no brand; require one when editing.
                if (draft.brand.isBlank()) throw new IllegalArgumentException("برند محصول را وارد کنید.");
                draft.step = Step.DESCRIPTION;
            }
            case DESCRIPTION -> { if (!keep) draft.description = text; draft.step = Step.CATEGORY; }
            case CATEGORY -> {
                if (!keep) {
                    if (Arrays.stream(ProductCategory.values()).noneMatch(c -> c.getCode().equals(text)))
                        throw new IllegalArgumentException("کد دسته‌بندی نامعتبر است.");
                    draft.category = text;
                }
                if (draft.id != null) {
                    catalog.update(actor, draft.id, draft.category, draft.name, draft.brand, draft.description, draft.imagePath);
                    saved(actor); return true;
                }
                draft.step = Step.STOCK;
            }
            case STOCK -> {
                catalog.create(actor, draft.category, draft.name, draft.brand, draft.description, draft.imagePath, BotFormatting.quantity(text));
                saved(actor); return true;
            }
        }
        prompt(actor);
        return true;
    }
    private static String required(String text) {
        if (text.isBlank()) throw new IllegalArgumentException("این مقدار نباید خالی باشد.");
        return text.trim();
    }
    private void saved(String actor) { reset(actor); client.sendMessage(actor, "✅ محصول ذخیره شد. قیمت را از مدیریت قیمت محصولات ثبت کنید."); showProducts(actor); }
    private void prompt(String actor) {
        Draft draft = drafts.get(actor);
        String text = switch (draft.step) {
            case NAME -> "نام محصول را وارد کنید:";
            case BRAND -> "برند محصول را وارد کنید:";
            case DESCRIPTION -> "توضیحات محصول را وارد کنید:";
            case CATEGORY -> "کد دسته‌بندی را وارد کنید:\n" + String.join("\n", Arrays.stream(ProductCategory.values()).map(c -> c.getCode() + " — " + c.getTitle()).toList());
            case STOCK -> "موجودی اولیه را به صورت عدد صحیح نامنفی وارد کنید:";
        };
        client.sendMessage(actor, text + (draft.id == null ? "" : "\nبرای حفظ مقدار قبلی - را بفرستید.") + "\nبرای انصراف «لغو» را بفرستید.");
    }
}
